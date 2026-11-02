package com.shimeta.service;

import com.shimeta.model.Agent;
import com.shimeta.model.AppUser;
import com.shimeta.model.CreateOpenApiKeyRequest;
import com.shimeta.model.OpenApiKey;
import com.shimeta.model.UserRole;
import com.shimeta.model.UserStatus;
import com.shimeta.repository.AgentRepository;
import com.shimeta.repository.OpenApiKeyRepository;
import com.shimeta.repository.UserRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.security.OpenApiScopes;
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
        return create(request, actor);
    }

    /** 能调用该智能体对话的凭证：专属于它的，以及不限智能体范围的；按当前用户可见范围过滤，不含已吊销 */
    public List<Map<String, Object>> credentialsForAgent(Agent agent, CurrentActor actor) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (OpenApiKey key : listForActor(actor)) {
            if (!canChatWith(key, agent.getId()) || "REVOKED".equalsIgnoreCase(key.getStatus())) {
                continue;
            }
            Map<String, Object> view = toView(key);
            view.put("binding", isDedicated(key, agent.getId()) ? "DEDICATED" : "ALL_AGENTS");
            view.put("usable", isUsable(key));
            result.add(view);
        }
        return result;
    }

    /**
     * 调试页「重新生成」：有专属于该智能体、当前用户可管理的启用凭证时轮换它（新 Key 立即生效，旧 Key 进入宽限期），
     * 否则签发一条新的专属对话凭证。返回值统一带 plaintext 与 key。
     */
    @Transactional
    public Map<String, Object> rotateOrIssueForAgent(Agent agent, CurrentActor actor, Integer graceHours) {
        Optional<OpenApiKey> current = listForActor(actor).stream()
                .filter(k -> "ACTIVE".equalsIgnoreCase(k.getStatus()) && isDedicated(k, agent.getId()) && isUsable(k))
                .findFirst();
        if (current.isEmpty()) {
            Map<String, Object> created = createChatKeyForAgent(agent, actor);
            created.put("mode", "ISSUED");
            return created;
        }
        Map<String, Object> rotated = rotate(current.get().getId(), graceHours, actor);
        Map<String, Object> result = new LinkedHashMap<>(rotated);
        result.put("mode", "ROTATED");
        result.put("key", rotated.get("newKey"));
        result.put("plaintext", rotated.get("newPlaintext"));
        return result;
    }

    private static boolean canChatWith(OpenApiKey key, String agentId) {
        List<String> scopes = key.getScopes() == null ? List.of() : key.getScopes();
        if (!scopes.contains(OpenApiScopes.CHAT)) {
            return false;
        }
        List<String> agentScope = key.getAgentScope();
        return agentScope == null || agentScope.isEmpty() || agentScope.contains(agentId);
    }

    private static boolean isDedicated(OpenApiKey key, String agentId) {
        List<String> agentScope = key.getAgentScope();
        return agentScope != null && agentScope.size() == 1 && agentScope.contains(agentId);
    }

    /** 当前能否通过鉴权：启用且未过期，或轮换中且仍在宽限期内 */
    private static boolean isUsable(OpenApiKey key) {
        LocalDateTime now = LocalDateTime.now();
        if (key.getExpiresAt() != null && now.isAfter(key.getExpiresAt())) {
            return false;
        }
        if ("ROTATING".equalsIgnoreCase(key.getStatus())) {
            return key.getGraceExpiresAt() == null || now.isBefore(key.getGraceExpiresAt());
        }
        return "ACTIVE".equalsIgnoreCase(key.getStatus());
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
            return Optional.of(new ResolvedKey(k));
        }
        return Optional.empty();
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

    public record ResolvedKey(OpenApiKey key) {
    }
}
