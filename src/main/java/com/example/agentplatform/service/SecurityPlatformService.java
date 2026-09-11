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
    private final GuardrailPolicyService guardrailPolicyService;

    public SecurityPlatformService(OpenApiKeyRepository keyRepository,
                                   ClientCredentialRepository clientRepository,
                                   GuardrailPolicyRepository policyRepository,
                                   AuditEventRepository auditEventRepository,
                                   ExternalIdentityRepository identityRepository,
                                   AuditRecorder auditRecorder,
                                   OwnerNameResolver ownerNameResolver,
                                   GuardrailPolicyService guardrailPolicyService) {
        this.keyRepository = keyRepository;
        this.clientRepository = clientRepository;
        this.policyRepository = policyRepository;
        this.auditEventRepository = auditEventRepository;
        this.identityRepository = identityRepository;
        this.auditRecorder = auditRecorder;
        this.ownerNameResolver = ownerNameResolver;
        this.guardrailPolicyService = guardrailPolicyService;
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
        return guardrailPolicyService.getEffectivePolicy(ownerId);
    }

    @Transactional
    public GuardrailPolicy savePolicy(GuardrailPolicy incoming, CurrentActor actor) {
        GuardrailPolicy saved = guardrailPolicyService.savePolicy(incoming, actor);
        auditRecorder.record("policy.update", "POLICY", saved.getOwnerId(), "SUCCESS", null, saved.getKillSwitch() ? "HIGH" : "LOW", null);
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

    @Transactional
    public Map<String, Object> batchApprove(List<String> ids, CurrentActor actor) {
        if (actor == null || actor.isViewer()) {
            throw new IllegalStateException("无权批量审批终端");
        }
        if (ids == null || ids.isEmpty()) {
            return Map.of("approvedCount", 0);
        }
        int count = 0;
        for (String id : ids) {
            ClientCredential client = clientRepository.findById(id).orElse(null);
            if (client != null && "PENDING".equalsIgnoreCase(client.getStatus())) {
                if (actor.isSuperAdmin() || client.getOwnerId().equals(actor.getUserId())) {
                    client.setStatus("ACTIVE");
                    clientRepository.save(client);
                    auditRecorder.record("client.active", "CLIENT", id, "SUCCESS", null, "MEDIUM", "批量审批通过: " + client.getClientId());
                    count++;
                }
            }
        }
        return Map.of("approvedCount", count);
    }

    @Transactional(readOnly = true)
    public String exportClientsCsv(CurrentActor actor) {
        List<ClientCredential> list = listClients(actor);
        StringBuilder sb = new StringBuilder();
        sb.append("\uFEFF");
        sb.append("ID,终端类型,终端标识,标签名称,所属开发者,状态,限流RPM,每日Token配额,授权智能体,最近活跃时间\n");
        for (ClientCredential c : list) {
            sb.append(escapeCsv(c.getId())).append(",")
              .append(escapeCsv(c.getClientType())).append(",")
              .append(escapeCsv(c.getClientId())).append(",")
              .append(escapeCsv(c.getLabel())).append(",")
              .append(escapeCsv(c.getOwnerUsername() != null ? c.getOwnerUsername() : c.getOwnerId())).append(",")
              .append(escapeCsv(c.getStatus())).append(",")
              .append(c.getRateLimitRpm() != null ? c.getRateLimitRpm() : "").append(",")
              .append(c.getDailyTokenQuota() != null ? c.getDailyTokenQuota() : "").append(",")
              .append(escapeCsv(c.getAgentScope() != null ? String.join(";", c.getAgentScope()) : "")).append(",")
              .append(c.getLastSeenAt() != null ? c.getLastSeenAt().toString() : "").append("\n");
        }
        return sb.toString();
    }

    @Transactional
    public Map<String, Object> importClientsCsv(String csvContent, CurrentActor actor) {
        if (actor == null || actor.isViewer()) {
            throw new IllegalStateException("无权导入接入终端");
        }
        String ownerId = actor.getUserId();
        if (csvContent == null || csvContent.isBlank()) {
            throw new IllegalArgumentException("CSV 内容不能为空");
        }
        String[] lines = csvContent.split("\\r?\\n");
        int success = 0;
        int failed = 0;
        java.util.List<String> errors = new java.util.ArrayList<>();

        boolean isHeader = true;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("\uFEFF")) trimmed = trimmed.substring(1).trim();
            if (isHeader) {
                isHeader = false;
                if (trimmed.toLowerCase().contains("type") || trimmed.contains("类型") || trimmed.contains("标识")) {
                    continue;
                }
            }
            String[] cols = trimmed.split(",", -1);
            if (cols.length < 2) {
                failed++;
                errors.add("行格式错误: " + trimmed);
                continue;
            }
            String clientType;
            String clientId;
            String label = "";
            String agentScopeStr = "";
            Integer rpm = null;
            Long quota = null;

            java.util.Set<String> validTypes = java.util.Set.of("SN", "MAC", "IMEI", "APP_ID", "CUSTOM_KEY");
            if (cols.length >= 3 && !validTypes.contains(cols[0].trim().toUpperCase()) && validTypes.contains(cols[1].trim().toUpperCase())) {
                // Exported format: ID, 终端类型, 终端标识, 标签名称, 所属开发者, 状态, 限流RPM, 每日Token配额, 授权智能体, 最近活跃时间
                clientType = cols[1].trim().toUpperCase();
                clientId = cols[2].trim();
                if (cols.length > 3) label = cols[3].trim();
                if (cols.length > 6 && !cols[6].trim().isEmpty()) {
                    try { rpm = Integer.parseInt(cols[6].trim()); } catch (Exception ignored) {}
                }
                if (cols.length > 7 && !cols[7].trim().isEmpty()) {
                    try { quota = Long.parseLong(cols[7].trim()); } catch (Exception ignored) {}
                }
                if (cols.length > 8) agentScopeStr = cols[8].trim();
            } else {
                // Standard import format: 终端类型, 终端标识, 标签名称, 授权智能体, 限流RPM, 每日Token配额
                clientType = cols[0].trim().toUpperCase();
                clientId = cols[1].trim();
                if (cols.length > 2) label = cols[2].trim();
                if (cols.length > 3) agentScopeStr = cols[3].trim();
                if (cols.length > 4 && !cols[4].trim().isEmpty()) {
                    try { rpm = Integer.parseInt(cols[4].trim()); } catch (Exception ignored) {}
                }
                if (cols.length > 5 && !cols[5].trim().isEmpty()) {
                    try { quota = Long.parseLong(cols[5].trim()); } catch (Exception ignored) {}
                }
            }

            try {
                ClientCredential item = new ClientCredential();
                item.setOwnerId(ownerId);
                item.setClientType(clientType);
                item.setClientId(clientId);
                item.setLabel(label.isBlank() ? null : label);
                item.setRateLimitRpm(rpm);
                item.setDailyTokenQuota(quota);
                if (!agentScopeStr.isBlank()) {
                    item.setAgentScope(java.util.List.of(agentScopeStr.split(";")));
                }
                saveClient(item, actor);
                success++;
            } catch (Exception e) {
                failed++;
                errors.add(clientId + ": " + e.getMessage());
            }
        }
        return Map.of("total", success + failed, "imported", success, "failed", failed, "errors", errors);
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String policyOwner(CurrentActor actor) {
        if (actor == null) {
            return "GLOBAL";
        }
        return actor.isSuperAdmin() ? "GLOBAL" : actor.getUserId();
    }
}
