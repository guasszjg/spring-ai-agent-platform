package com.example.agentplatform.service;

import com.example.agentplatform.model.ClientCredential;
import com.example.agentplatform.model.GuardrailPolicy;
import com.example.agentplatform.repository.AuditEventRepository;
import com.example.agentplatform.repository.ClientCredentialRepository;
import com.example.agentplatform.repository.ExternalIdentityRepository;
import com.example.agentplatform.repository.GuardrailPolicyRepository;
import com.example.agentplatform.repository.OpenApiKeyRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.security.OpenApiAuthFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SecurityPlatformService {

    private final OpenApiKeyRepository keyRepository;
    private final ClientCredentialRepository clientRepository;
    private final GuardrailPolicyRepository policyRepository;
    private final AuditEventRepository auditEventRepository;
    private final ExternalIdentityRepository identityRepository;
    private final AuditRecorder auditRecorder;
    private final OwnerNameResolver ownerNameResolver;

    public SecurityPlatformService(OpenApiKeyRepository keyRepository,
                                   ClientCredentialRepository clientRepository,
                                   GuardrailPolicyRepository policyRepository,
                                   AuditEventRepository auditEventRepository,
                                   ExternalIdentityRepository identityRepository,
                                   AuditRecorder auditRecorder,
                                   OwnerNameResolver ownerNameResolver) {
        this.keyRepository = keyRepository;
        this.clientRepository = clientRepository;
        this.policyRepository = policyRepository;
        this.auditEventRepository = auditEventRepository;
        this.identityRepository = identityRepository;
        this.auditRecorder = auditRecorder;
        this.ownerNameResolver = ownerNameResolver;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview(CurrentActor actor) {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        boolean admin = actor != null && actor.isSuperAdmin();
        String ownerId = actor != null ? actor.getUserId() : null;
        long activeKeys = admin ? keyRepository.countByStatus("ACTIVE")
                : (ownerId != null ? keyRepository.countByOwnerIdAndStatus(ownerId, "ACTIVE") : 0);
        long activeClients = admin ? clientRepository.countByStatus("ACTIVE")
                : (ownerId != null ? clientRepository.countByOwnerIdAndStatus(ownerId, "ACTIVE") : 0);
        long denied = admin
                ? auditEventRepository.countByOccurredAtAfterAndResult(start, "DENIED")
                : (ownerId != null ? auditEventRepository.countByOwnerIdAndOccurredAtAfterAndResult(ownerId, start, "DENIED") : 0);
        long success = admin
                ? auditEventRepository.countByOccurredAtAfterAndResult(start, "SUCCESS")
                : (ownerId != null ? auditEventRepository.countByOwnerIdAndOccurredAtAfterAndResult(ownerId, start, "SUCCESS") : 0);
        long highRisk = auditEventRepository.countByOccurredAtAfterAndRiskLevel(start, "HIGH");
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("openCallsToday", success + denied);
        map.put("deniedToday", denied);
        map.put("highRiskToday", highRisk);
        map.put("activeKeys", activeKeys);
        map.put("activeClients", activeClients);
        map.put("pendingIdentities", identityRepository.countByStatus("PENDING_SYNC") + identityRepository.countByStatus("SYNC_FAILED"));
        map.put("recentHighRisk", auditEventRepository.findTop10ByRiskLevelOrderByOccurredAtDesc("HIGH"));
        GuardrailPolicy policy = loadPolicy(actor);
        map.put("killSwitch", policy.getKillSwitch());
        map.put("clientPolicy", policy.getClientPolicy());
        return map;
    }

    @Transactional(readOnly = true)
    public GuardrailPolicy loadPolicy(CurrentActor actor) {
        String ownerId = policyOwner(actor);
        return policyRepository.findById(ownerId).orElseGet(() -> {
            GuardrailPolicy created = new GuardrailPolicy();
            created.setOwnerId(ownerId);
            return created;
        });
    }

    @Transactional
    public GuardrailPolicy savePolicy(GuardrailPolicy incoming, CurrentActor actor) {
        if (actor == null || actor.isViewer()) {
            throw new IllegalStateException("无权修改护栏策略");
        }
        String ownerId = policyOwner(actor);
        GuardrailPolicy policy = policyRepository.findById(ownerId).orElseGet(() -> {
            GuardrailPolicy created = new GuardrailPolicy();
            created.setOwnerId(ownerId);
            return created;
        });
        policy.setClientPolicy(incoming.getClientPolicy());
        policy.setDefaultRpm(incoming.getDefaultRpm());
        policy.setDefaultDailyTokens(incoming.getDefaultDailyTokens());
        policy.setMaxInputChars(incoming.getMaxInputChars());
        policy.setMaxHistoryTurns(incoming.getMaxHistoryTurns());
        policy.setPiiMask(incoming.getPiiMask());
        policy.setSensitiveWords(incoming.getSensitiveWords());
        policy.setSensitiveAction(incoming.getSensitiveAction());
        policy.setPromptInjection(incoming.getPromptInjection());
        policy.setOutputGuard(incoming.getOutputGuard());
        policy.setAllowedHours(incoming.getAllowedHours());
        policy.setKillSwitch(incoming.getKillSwitch());
        GuardrailPolicy saved = policyRepository.save(policy);
        auditRecorder.record("policy.update", "POLICY", ownerId, "SUCCESS", null, saved.getKillSwitch() ? "HIGH" : "LOW", null);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ClientCredential> listClients(CurrentActor actor) {
        List<ClientCredential> list;
        if (actor != null && actor.isSuperAdmin()) {
            list = clientRepository.findAllByOrderByUpdatedAtDesc();
        } else {
            list = actor != null ? clientRepository.findByOwnerIdOrderByUpdatedAtDesc(actor.getUserId()) : List.of();
        }
        Map<String, String> names = ownerNameResolver.usernames(
                list.stream().map(ClientCredential::getOwnerId).toList());
        for (ClientCredential client : list) {
            client.setOwnerUsername(OwnerNameResolver.lookup(names, client.getOwnerId()));
        }
        return list;
    }

    @Transactional
    public ClientCredential saveClient(ClientCredential incoming, CurrentActor actor) {
        if (actor == null || actor.isViewer()) {
            throw new IllegalStateException("无权管理接入终端");
        }
        String ownerId = incoming.getOwnerId();
        if (ownerId == null || ownerId.isBlank() || !actor.isSuperAdmin()) {
            ownerId = actor.getUserId();
        }
        if (incoming.getClientType() == null || incoming.getClientId() == null) {
            throw new IllegalArgumentException("请填写终端类型和标识");
        }
        String type = incoming.getClientType().trim().toUpperCase();
        String normalized = OpenApiAuthFilter.normalizeClientId(type, incoming.getClientId());
        String hash = OpenApiKeyService.sha256(normalized);
        ClientCredential entity = incoming.getId() != null
                ? clientRepository.findById(incoming.getId()).orElse(new ClientCredential())
                : new ClientCredential();
        if (entity.getId() != null && !actor.isSuperAdmin() && !ownerId.equals(entity.getOwnerId())) {
            throw new IllegalStateException("无权修改他人终端");
        }
        entity.setOwnerId(ownerId);
        entity.setClientType(type);
        entity.setClientId("CUSTOM_KEY".equals(type) && normalized.length() > 8
                ? normalized.substring(0, 4) + "****" + normalized.substring(normalized.length() - 4)
                : normalized);
        entity.setClientIdHash(hash);
        entity.setLabel(incoming.getLabel());
        entity.setAgentScope(incoming.getAgentScope());
        entity.setRateLimitRpm(incoming.getRateLimitRpm());
        entity.setDailyTokenQuota(incoming.getDailyTokenQuota());
        entity.setStatus(incoming.getStatus() != null ? incoming.getStatus() : "ACTIVE");
        entity.setExpiresAt(incoming.getExpiresAt());
        entity.setAttributes(incoming.getAttributes());
        ClientCredential saved = clientRepository.save(entity);
        auditRecorder.record("client.save", "CLIENT", saved.getId(), "SUCCESS", null, "LOW", type);
        return saved;
    }

    @Transactional
    public ClientCredential approve(String id, CurrentActor actor) {
        return updateClientStatus(id, "ACTIVE", actor);
    }

    @Transactional
    public ClientCredential updateClientStatus(String id, String status, CurrentActor actor) {
        if (actor == null || actor.isViewer()) {
            throw new IllegalStateException("无权变更终端状态");
        }
        ClientCredential client = clientRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("终端不存在"));
        if (!actor.isSuperAdmin() && !client.getOwnerId().equals(actor.getUserId())) {
            throw new IllegalStateException("无权管理该终端");
        }
        String normalized = status != null ? status.trim().toUpperCase() : "";
        if (!"ACTIVE".equals(normalized) && !"DISABLED".equals(normalized) && !"PENDING".equals(normalized)) {
            throw new IllegalArgumentException("无效状态");
        }
        client.setStatus(normalized);
        ClientCredential saved = clientRepository.save(client);
        auditRecorder.record("client." + normalized.toLowerCase(), "CLIENT", id, "SUCCESS", null, "MEDIUM", client.getClientId());
        return saved;
    }

    @Transactional
    public void deleteClient(String id, CurrentActor actor) {
        ClientCredential client = clientRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("终端不存在"));
        if (actor == null || (!actor.isSuperAdmin() && !client.getOwnerId().equals(actor.getUserId()))) {
            throw new IllegalStateException("无权删除该终端");
        }
        clientRepository.delete(client);
    }

    private String policyOwner(CurrentActor actor) {
        if (actor == null) {
            return "GLOBAL";
        }
        return actor.isSuperAdmin() ? "GLOBAL" : actor.getUserId();
    }
}
