package com.example.agentplatform.service;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.CreateOpenApiKeyRequest;
import com.example.agentplatform.model.OpenApiKey;
import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.model.UserStatus;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.repository.OpenApiKeyRepository;
import com.example.agentplatform.repository.UserRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.security.OpenApiScopes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class OpenApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OpenApiKeyRepository keyRepository;
    private final UserRepository userRepository;
    private final AgentRepository agentRepository;
    private final AuditRecorder auditRecorder;
    private final OwnerNameResolver ownerNameResolver;

    public OpenApiKeyService(OpenApiKeyRepository keyRepository,
                             UserRepository userRepository,
                             AgentRepository agentRepository,
                             AuditRecorder auditRecorder,
                             OwnerNameResolver ownerNameResolver) {
        this.keyRepository = keyRepository;
        this.userRepository = userRepository;
        this.agentRepository = agentRepository;
        this.auditRecorder = auditRecorder;
        this.ownerNameResolver = ownerNameResolver;
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("无法计算密钥哈希", e);
        }
    }

    public static String generatePlaintext() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return "sk-live-" + HexFormat.of().formatHex(bytes);
    }

    @Transactional(readOnly = true)
    public List<OpenApiKey> listForActor(CurrentActor actor) {
        if (actor == null) {
            return List.of();
        }
        if (actor.isSuperAdmin()) {
            return keyRepository.findAllByOrderByCreatedAtDesc();
        }
        return keyRepository.findByOwnerIdOrderByCreatedAtDesc(actor.getUserId());
    }

    @Transactional
    public Map<String, Object> create(CreateOpenApiKeyRequest request, CurrentActor actor) {
        if (actor == null || actor.isViewer()) {
            throw new IllegalStateException("无权签发开放凭证");
        }
        String ownerId = request.getOwnerId();
        if (ownerId == null || ownerId.isBlank()) {
            ownerId = actor.getUserId();
        }
        if (!actor.isSuperAdmin() && !ownerId.equals(actor.getUserId())) {
            throw new IllegalStateException("不能替其他开发者签发凭证");
        }
        AppUser owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("目标开发者不存在"));
        if (!UserStatus.ACTIVE.equals(owner.getStatus()) && !UserStatus.PENDING_PASSWORD.equals(owner.getStatus())) {
            throw new IllegalArgumentException("目标账号未激活，不能签发凭证");
        }
        UserRole ownerRole = UserRole.fromRaw(owner.getRole());
        OpenApiScopes.validateAgainstRole(ownerRole, request.getScopes());
        List<String> agentScope = request.getAgentScope() == null ? new ArrayList<>() : new ArrayList<>(request.getAgentScope());
        if (ownerRole == UserRole.SUPER_ADMIN && agentScope.isEmpty()) {
            throw new IllegalArgumentException("超级管理员凭证必须限定智能体范围，禁止 agent_scope 为空");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("请填写凭证名称");
        }

        String plaintext = generatePlaintext();
        OpenApiKey key = new OpenApiKey();
        key.setOwnerId(owner.getId());
        key.setCreatedBy(actor.getUserId());
        key.setIssuerAuthVersion(actor.getAuthVersion());
        key.setName(request.getName().trim());
        key.setKeyPrefix(plaintext.substring(0, Math.min(16, plaintext.length())));
        key.setKeyHash(sha256(plaintext));
        key.setScopes(request.getScopes());
        key.setAgentScope(agentScope);
        key.setIpAllowlist(request.getIpAllowlist());
        key.setRateLimitRpm(request.getRateLimitRpm());
        key.setDailyTokenQuota(request.getDailyTokenQuota());
        if (request.getExpiresAt() != null && !request.getExpiresAt().isBlank()) {
            try {
                key.setExpiresAt(LocalDateTime.parse(request.getExpiresAt()));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("过期时间格式无效");
            }
        }
        key = keyRepository.save(key);
        auditRecorder.record("key.create", "API_KEY", key.getId(), "SUCCESS", null, "LOW", key.getKeyPrefix());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", toView(key));
        result.put("plaintext", plaintext);
        return result;
    }

    @Transactional
    public Map<String, Object> createChatKeyForAgent(Agent agent, CurrentActor actor) {
        CreateOpenApiKeyRequest request = new CreateOpenApiKeyRequest();
        request.setName((agent.getName() != null ? agent.getName() : "智能体") + " 对话凭证");
        String ownerId = agent.getOwnerId();
        if (ownerId == null || ownerId.isBlank() || "system".equalsIgnoreCase(ownerId)
                || userRepository.findById(ownerId).isEmpty()) {
            ownerId = actor.getUserId();
        }
        request.setOwnerId(ownerId);
        request.setScopes(List.of(OpenApiScopes.CHAT));
        request.setAgentScope(List.of(agent.getId()));
        Map<String, Object> created = create(request, actor);
        String plaintext = (String) created.get("plaintext");
        agent.setApiKey(plaintext);
        agentRepository.save(agent);
        return created;
    }

    @Transactional
    public Map<String, Object> rotate(String id, Integer graceHours, CurrentActor actor) {
        if (actor == null || actor.isViewer()) {
            throw new IllegalStateException("无权轮换开放凭证");
        }
        OpenApiKey oldKey = requireOwned(id, actor);
        if ("REVOKED".equalsIgnoreCase(oldKey.getStatus()) || "DISABLED".equalsIgnoreCase(oldKey.getStatus())) {
            throw new IllegalArgumentException("当前凭证不可用，无法进行轮换");
        }

        int hours = (graceHours != null && graceHours > 0) ? graceHours : 24;
        LocalDateTime graceExpiresAt = LocalDateTime.now().plusHours(hours);

        String newPlaintext = generatePlaintext();
        OpenApiKey newKey = new OpenApiKey();
        newKey.setId("oak-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        newKey.setOwnerId(oldKey.getOwnerId());
        newKey.setCreatedBy(actor.getUserId());
        newKey.setIssuerAuthVersion(actor.getAuthVersion());
        newKey.setName(oldKey.getName() + " (轮换)");
        newKey.setKeyPrefix(newPlaintext.substring(0, Math.min(16, newPlaintext.length())));
        newKey.setKeyHash(sha256(newPlaintext));
        newKey.setScopes(new ArrayList<>(oldKey.getScopes()));
        newKey.setAgentScope(new ArrayList<>(oldKey.getAgentScope()));
        newKey.setIpAllowlist(new ArrayList<>(oldKey.getIpAllowlist()));
        newKey.setRateLimitRpm(oldKey.getRateLimitRpm());
        newKey.setDailyTokenQuota(oldKey.getDailyTokenQuota());
        newKey.setStatus("ACTIVE");
        newKey = keyRepository.save(newKey);

        oldKey.setStatus("ROTATING");
        oldKey.setGraceExpiresAt(graceExpiresAt);
        oldKey.setRotatedToKeyId(newKey.getId());
        keyRepository.save(oldKey);

        auditRecorder.record("key.rotate", "API_KEY", oldKey.getId(), "SUCCESS", null, "MEDIUM",
                "轮换至新Key: " + newKey.getKeyPrefix() + "，宽限期 " + hours + " 小时");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("newKey", toView(newKey));
        result.put("newPlaintext", newPlaintext);
        result.put("oldKey", toView(oldKey));
        result.put("graceHours", hours);
        result.put("graceExpiresAt", graceExpiresAt);
        return result;
    }

    @Transactional
    public OpenApiKey updateStatus(String id, String status, CurrentActor actor) {
        OpenApiKey key = requireOwned(id, actor);
        String normalized = status != null ? status.trim().toUpperCase() : "";
        if (!"ACTIVE".equals(normalized) && !"DISABLED".equals(normalized) && !"REVOKED".equals(normalized)) {
            throw new IllegalArgumentException("无效状态");
        }
        key.setStatus(normalized);
        if ("REVOKED".equals(normalized)) {
            key.setRevokedAt(LocalDateTime.now());
        }
        OpenApiKey saved = keyRepository.save(key);
        auditRecorder.record("key." + normalized.toLowerCase(), "API_KEY", id, "SUCCESS", null, "MEDIUM", key.getKeyPrefix());
        return saved;
    }

    @Transactional
    public void delete(String id, CurrentActor actor) {
        if (actor == null || actor.isViewer()) {
            throw new IllegalStateException("无权删除开放凭证");
        }
        OpenApiKey key = requireOwned(id, actor);
        keyRepository.delete(key);
        auditRecorder.record("key.delete", "API_KEY", id, "SUCCESS", null, "HIGH", key.getKeyPrefix());
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedKey> resolvePlaintext(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return Optional.empty();
        }
        Optional<OpenApiKey> stored = keyRepository.findByKeyHash(sha256(plaintext.trim()));
        if (stored.isPresent()) {
            OpenApiKey k = stored.get();
            if ("ROTATING".equals(k.getStatus()) && k.getGraceExpiresAt() != null && LocalDateTime.now().isAfter(k.getGraceExpiresAt())) {
                k.setStatus("REVOKED");
                k.setRevokedAt(LocalDateTime.now());
                keyRepository.save(k);
            }
            return Optional.of(ResolvedKey.fromStored(k));
        }
        return agentRepository.findByApiKey(plaintext.trim()).map(ResolvedKey::fromLegacyAgent);
    }

    @Transactional
    public void touchUsage(OpenApiKey key, String ip) {
        if (key == null || Boolean.TRUE.equals(key.getMigrated()) && key.getId() == null) {
            return;
        }
        if (key.getId() == null) {
            return;
        }
        keyRepository.findById(key.getId()).ifPresent(stored -> {
            stored.setLastUsedAt(LocalDateTime.now());
            stored.setLastUsedIp(ip);
            keyRepository.save(stored);
        });
    }

    @Transactional
    public void migrateLegacyAgentKeys() {
        for (Agent agent : agentRepository.findAll()) {
            String raw = agent.getApiKey();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String hash = sha256(raw);
            if (keyRepository.findByKeyHash(hash).isPresent()) {
                continue;
            }
            OpenApiKey key = new OpenApiKey();
            key.setOwnerId(agent.getOwnerId() != null ? agent.getOwnerId() : "system");
            key.setCreatedBy(key.getOwnerId());
            key.setIssuerAuthVersion(1);
            key.setName((agent.getName() != null ? agent.getName() : "智能体") + " 兼容凭证");
            key.setKeyPrefix(raw.substring(0, Math.min(16, raw.length())));
            key.setKeyHash(hash);
            key.setScopes(List.of(OpenApiScopes.CHAT));
            key.setAgentScope(List.of(agent.getId()));
            key.setMigrated(true);
            key.setExpiresAt(LocalDateTime.now().plusDays(7));
            keyRepository.save(key);
        }
    }

    public OpenApiKey requireOwned(String id, CurrentActor actor) {
        OpenApiKey key = keyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("凭证不存在"));
        if (actor == null) {
            throw new IllegalStateException("未登录");
        }
        if (!actor.isSuperAdmin() && !key.getOwnerId().equals(actor.getUserId())) {
            throw new IllegalStateException("无权管理该凭证");
        }
        return key;
    }

    public Map<String, Object> toView(OpenApiKey key) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", key.getId());
        map.put("ownerId", key.getOwnerId());
        map.put("ownerUsername", ownerNameResolver.username(key.getOwnerId()));
        map.put("createdBy", key.getCreatedBy());
        map.put("name", key.getName());
        map.put("keyPrefix", key.getKeyPrefix());
        map.put("scopes", key.getScopes());
        map.put("agentScope", key.getAgentScope());
        map.put("ipAllowlist", key.getIpAllowlist());
        map.put("rateLimitRpm", key.getRateLimitRpm());
        map.put("dailyTokenQuota", key.getDailyTokenQuota());
        map.put("status", key.getStatus());
        map.put("expiresAt", key.getExpiresAt());
        map.put("revokedAt", key.getRevokedAt());
        map.put("graceExpiresAt", key.getGraceExpiresAt());
        map.put("rotatedToKeyId", key.getRotatedToKeyId());
        map.put("lastUsedAt", key.getLastUsedAt());
        map.put("lastUsedIp", key.getLastUsedIp());
        map.put("migrated", key.getMigrated());
        map.put("createdAt", key.getCreatedAt());
        return map;
    }

    public record ResolvedKey(OpenApiKey key, Agent legacyAgent) {
        static ResolvedKey fromStored(OpenApiKey key) {
            return new ResolvedKey(key, null);
        }

        static ResolvedKey fromLegacyAgent(Agent agent) {
            OpenApiKey synthetic = new OpenApiKey();
            synthetic.setId("legacy-" + agent.getId());
            synthetic.setOwnerId(agent.getOwnerId() != null ? agent.getOwnerId() : "system");
            synthetic.setCreatedBy(synthetic.getOwnerId());
            synthetic.setName("兼容智能体凭证");
            synthetic.setKeyPrefix(agent.getApiKey() != null ? agent.getApiKey().substring(0, Math.min(16, agent.getApiKey().length())) : "sk-agent-");
            synthetic.setScopes(List.of(OpenApiScopes.CHAT));
            synthetic.setAgentScope(List.of(agent.getId()));
            synthetic.setStatus("ACTIVE");
            synthetic.setMigrated(true);
            return new ResolvedKey(synthetic, agent);
        }

        public boolean isLegacy() {
            return legacyAgent != null;
        }
    }
}
