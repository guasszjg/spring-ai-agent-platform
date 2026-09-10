package com.example.agentplatform.service;

import com.example.agentplatform.config.SecretCrypto;
import com.example.agentplatform.identity.HttpTemplateIdentityAdapter;
import com.example.agentplatform.identity.IdentitySyncResult;
import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.ExternalIdentity;
import com.example.agentplatform.model.IdentityBindRequest;
import com.example.agentplatform.model.IdentityProvider;
import com.example.agentplatform.repository.ExternalIdentityRepository;
import com.example.agentplatform.repository.IdentityProviderRepository;
import com.example.agentplatform.repository.UserRepository;
import com.example.agentplatform.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IdentitySyncService {

    private final IdentityProviderRepository providerRepository;
    private final ExternalIdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final HttpTemplateIdentityAdapter adapter;
    private final SecretCrypto secretCrypto;
    private final AuditRecorder auditRecorder;

    public IdentitySyncService(IdentityProviderRepository providerRepository,
                               ExternalIdentityRepository identityRepository,
                               UserRepository userRepository,
                               HttpTemplateIdentityAdapter adapter,
                               SecretCrypto secretCrypto,
                               AuditRecorder auditRecorder) {
        this.providerRepository = providerRepository;
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.adapter = adapter;
        this.secretCrypto = secretCrypto;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(readOnly = true)
    public List<IdentityProvider> listProviders() {
        return providerRepository.findAll();
    }

    @Transactional
    public IdentityProvider saveProvider(IdentityProvider incoming, String credentialPlain, String webhookPlain, CurrentActor actor) {
        if (actor == null || !actor.isSuperAdmin()) {
            throw new IllegalStateException("仅超级管理员可配置对接通道");
        }
        if (incoming.getCode() == null || incoming.getCode().isBlank()) {
            throw new IllegalArgumentException("请填写 provider code");
        }
        String code = incoming.getCode().trim().toLowerCase();
        IdentityProvider provider = incoming.getId() != null
                ? providerRepository.findById(incoming.getId()).orElse(new IdentityProvider())
                : providerRepository.findByCode(code).orElse(new IdentityProvider());
        if (provider.getId() == null && providerRepository.findByCode(code).isPresent()
                && (incoming.getId() == null || incoming.getId().isBlank())) {
            throw new IllegalArgumentException("provider code 已存在");
        }
        provider.setCode(code);
        provider.setName(incoming.getName() != null ? incoming.getName().trim() : code);
        provider.setEnabled(incoming.getEnabled());
        provider.setDirection(incoming.getDirection() != null ? incoming.getDirection() : "BIDIRECTIONAL");
        provider.setBaseUrl(incoming.getBaseUrl());
        provider.setAuthType(incoming.getAuthType());
        provider.setTimeoutMs(incoming.getTimeoutMs());
        provider.setOperations(incoming.getOperations() != null ? incoming.getOperations() : "{}");
        provider.setFieldMapping(incoming.getFieldMapping() != null ? incoming.getFieldMapping() : "{}");
        provider.setOnUserCreated(incoming.getOnUserCreated());
        provider.setOnUserDisabled(incoming.getOnUserDisabled());
        provider.setFailPolicy(incoming.getFailPolicy());
        if (credentialPlain != null && !credentialPlain.isBlank() && !credentialPlain.contains("*")) {
            provider.setCredentialEncrypted(secretCrypto.encrypt(credentialPlain));
        }
        if (webhookPlain != null && !webhookPlain.isBlank() && !webhookPlain.contains("*")) {
            provider.setWebhookSecretEncrypted(secretCrypto.encrypt(webhookPlain));
        }
        return providerRepository.save(provider);
    }

    public Map<String, Object> toProviderView(IdentityProvider provider) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", provider.getId());
        map.put("code", provider.getCode());
        map.put("name", provider.getName());
        map.put("enabled", provider.getEnabled());
        map.put("direction", provider.getDirection());
        map.put("baseUrl", provider.getBaseUrl());
        map.put("authType", provider.getAuthType());
        map.put("timeoutMs", provider.getTimeoutMs());
        map.put("operations", provider.getOperations());
        map.put("fieldMapping", provider.getFieldMapping());
        map.put("onUserCreated", provider.getOnUserCreated());
        map.put("onUserDisabled", provider.getOnUserDisabled());
        map.put("failPolicy", provider.getFailPolicy());
        map.put("credentialConfigured", provider.getCredentialEncrypted() != null && !provider.getCredentialEncrypted().isBlank());
        map.put("webhookConfigured", provider.getWebhookSecretEncrypted() != null && !provider.getWebhookSecretEncrypted().isBlank());
        map.put("updatedAt", provider.getUpdatedAt());
        return map;
    }

    @Transactional
    public List<ExternalIdentity> bindOnUserCreated(AppUser user, List<IdentityBindRequest> requests) {
        List<IdentityBindRequest> jobs = requests != null ? new ArrayList<>(requests) : new ArrayList<>();
        for (IdentityProvider provider : providerRepository.findByEnabledTrue()) {
            if ("OFF".equalsIgnoreCase(provider.getOnUserCreated())) {
                continue;
            }
            boolean already = jobs.stream().anyMatch(r -> provider.getCode().equalsIgnoreCase(r.getProvider()));
            if (!already) {
                IdentityBindRequest auto = new IdentityBindRequest();
                auto.setProvider(provider.getCode());
                auto.setMode(provider.getOnUserCreated());
                jobs.add(auto);
            }
        }
        List<ExternalIdentity> results = new ArrayList<>();
        for (IdentityBindRequest request : jobs) {
            if (request.getProvider() == null || request.getProvider().isBlank()) {
                continue;
            }
            results.add(bind(user.getId(), request, true));
        }
        return results;
    }

    @Transactional
    public ExternalIdentity bind(String userId, IdentityBindRequest request, boolean fromCreate) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        String mode = request.getMode() != null ? request.getMode().trim().toUpperCase() : "MANUAL";
        IdentityProvider provider = providerRepository.findByCode(request.getProvider().trim().toLowerCase()).orElse(null);
        String providerCode = provider != null ? provider.getCode() : request.getProvider().trim().toLowerCase();

        ExternalIdentity identity = new ExternalIdentity();
        identity.setUserId(user.getId());
        identity.setProviderId(provider != null ? provider.getId() : null);
        identity.setProviderCode(providerCode);
        identity.setDisplayName(request.getDisplayName());
        identity.setBindSource(switch (mode) {
            case "CREATE_REMOTE" -> "OUTBOUND_CREATE";
            case "BIND_EXISTING" -> "OUTBOUND_BIND";
            default -> "MANUAL";
        });

        if ("MANUAL".equals(mode)) {
            if (request.getExternalId() == null || request.getExternalId().isBlank()) {
                throw new IllegalArgumentException("手工绑定需要填写对方账号 ID");
            }
            ensureUnique(providerCode, request.getExternalId().trim(), null);
            identity.setExternalId(request.getExternalId().trim());
            identity.setStatus("ACTIVE");
            ExternalIdentity saved = identityRepository.save(identity);
            auditRecorder.record("identity.bind", "IDENTITY", saved.getId(), "SUCCESS", null, "LOW", providerCode);
            return saved;
        }

        if (provider == null || !provider.getEnabled()) {
            throw new IllegalArgumentException("对接通道不存在或未启用: " + providerCode);
        }

        identity.setStatus("PENDING_SYNC");
        identity.setExternalId(request.getExternalId() != null && !request.getExternalId().isBlank()
                ? request.getExternalId().trim()
                : "pending-" + UUID.randomUUID().toString().substring(0, 8));
        identity = identityRepository.save(identity);

        IdentitySyncResult result = "CREATE_REMOTE".equals(mode)
                ? adapter.createRemote(user, provider)
                : adapter.bind(user, provider, request.getExternalId());
        applySyncResult(identity, result, request.getExternalId(), "REQUIRED".equalsIgnoreCase(provider.getFailPolicy()) && fromCreate);
        return identityRepository.save(identity);
    }

    @Transactional
    public ExternalIdentity syncNow(String identityId) {
        ExternalIdentity identity = identityRepository.findById(identityId)
                .orElseThrow(() -> new IllegalArgumentException("绑定记录不存在"));
        IdentityProvider provider = providerRepository.findById(identity.getProviderId())
                .orElseGet(() -> providerRepository.findByCode(identity.getProviderCode()).orElse(null));
        if (provider == null) {
            throw new IllegalArgumentException("找不到对接通道");
        }
        AppUser user = userRepository.findById(identity.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        IdentitySyncResult result = "OUTBOUND_CREATE".equals(identity.getBindSource())
                ? adapter.createRemote(user, provider)
                : adapter.bind(user, provider, identity.getExternalId());
        applySyncResult(identity, result, identity.getExternalId(), false);
        return identityRepository.save(identity);
    }

    @Transactional(readOnly = true)
    public List<ExternalIdentity> listByUser(String userId) {
        return identityRepository.findByUserIdOrderByBoundAtDesc(userId);
    }

    @Transactional
    public void delete(String identityId, CurrentActor actor) {
        ExternalIdentity identity = identityRepository.findById(identityId)
                .orElseThrow(() -> new IllegalArgumentException("绑定记录不存在"));
        if (actor != null && !actor.isSuperAdmin() && !identity.getUserId().equals(actor.getUserId())) {
            throw new IllegalStateException("无权解绑该账号");
        }
        identityRepository.delete(identity);
        auditRecorder.record("identity.unbind", "IDENTITY", identityId, "SUCCESS", null, "LOW", identity.getProviderCode());
    }

    @Transactional
    public void deleteProvider(String id, CurrentActor actor) {
        if (actor == null || !actor.isSuperAdmin()) {
            throw new IllegalStateException("仅超级管理员可删除对接通道");
        }
        IdentityProvider provider = providerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("对接通道不存在"));
        for (ExternalIdentity identity : identityRepository.findByProviderCode(provider.getCode())) {
            identityRepository.delete(identity);
        }
        providerRepository.delete(provider);
        auditRecorder.record("identity.provider.delete", "IDENTITY_PROVIDER", id, "SUCCESS", null, "HIGH", provider.getCode());
    }

    public IdentitySyncResult probe(String providerId) {
        IdentityProvider provider = providerRepository.findById(providerId)
                .orElseThrow(() -> new IllegalArgumentException("对接通道不存在"));
        return adapter.probe(provider);
    }

    private void applySyncResult(ExternalIdentity identity, IdentitySyncResult result, String fallbackId, boolean required) {
        identity.setLastSyncedAt(LocalDateTime.now());
        if (result.success()) {
            if (result.externalId() != null && !result.externalId().startsWith("pending-")) {
                ensureUnique(identity.getProviderCode(), result.externalId(), identity.getId());
                identity.setExternalId(result.externalId());
            } else if (fallbackId != null && !fallbackId.isBlank()) {
                identity.setExternalId(fallbackId);
            }
            if (result.displayName() != null) {
                identity.setDisplayName(result.displayName());
            }
            identity.setAttributes(result.attributes());
            identity.setStatus("ACTIVE");
            identity.setLastSyncError(null);
            auditRecorder.record("identity.sync", "IDENTITY", identity.getId(), "SUCCESS", null, "LOW", identity.getProviderCode());
        } else {
            identity.setStatus("SYNC_FAILED");
            identity.setLastSyncError(result.error());
            auditRecorder.record("identity.sync", "IDENTITY", identity.getId(), "ERROR", "sync_failed", "MEDIUM", result.error());
            if (required) {
                throw new IllegalStateException("对方账号同步失败: " + result.error());
            }
        }
    }

    private void ensureUnique(String providerCode, String externalId, String excludeId) {
        identityRepository.findByProviderCodeAndExternalId(providerCode, externalId).ifPresent(existing -> {
            if (excludeId == null || !excludeId.equals(existing.getId())) {
                throw new IllegalArgumentException("该第三方账号已绑定其他开发者");
            }
        });
    }
}
