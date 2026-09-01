package com.example.agentplatform.service;

import com.example.agentplatform.config.SecretCrypto;
import com.example.agentplatform.model.GatewayModelOption;
import com.example.agentplatform.model.GatewayOverview;
import com.example.agentplatform.model.GatewayPolicy;
import com.example.agentplatform.model.GatewayProbeRequest;
import com.example.agentplatform.model.GatewayProbeResult;
import com.example.agentplatform.model.LlmProvider;
import com.example.agentplatform.model.LlmProviderRequest;
import com.example.agentplatform.model.LlmProviderType;
import com.example.agentplatform.model.LlmProviderView;
import com.example.agentplatform.repository.GatewayPolicyRepository;
import com.example.agentplatform.repository.LlmProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LlmGatewayService {

    public static final String POLICY_ID = "default";

    private final LlmProviderRepository providerRepository;
    private final GatewayPolicyRepository policyRepository;
    private final SecretCrypto secretCrypto;
    private final OpenAiCompatibleClient openAiClient;

    public LlmGatewayService(LlmProviderRepository providerRepository,
                             GatewayPolicyRepository policyRepository,
                             SecretCrypto secretCrypto,
                             OpenAiCompatibleClient openAiClient) {
        this.providerRepository = providerRepository;
        this.policyRepository = policyRepository;
        this.secretCrypto = secretCrypto;
        this.openAiClient = openAiClient;
    }

    @Transactional
    public GatewayOverview overview() {
        List<LlmProviderView> providers = providerRepository.findAllByOrderByBuiltinDescCreatedAtAsc()
                .stream()
                .map(this::toView)
                .toList();
        GatewayOverview overview = new GatewayOverview();
        overview.setProviders(providers);
        overview.setPolicy(ensurePolicy());
        overview.setConfiguredCount((int) providers.stream().filter(LlmProviderView::isConfigured).count());
        overview.setEnabledCount((int) providers.stream().filter(LlmProviderView::isEnabled).count());
        overview.setReadyCount((int) providers.stream().filter(item -> item.isEnabled() && item.isConfigured()).count());
        return overview;
    }

    @Transactional
    public List<GatewayModelOption> listModels() {
        List<GatewayModelOption> options = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (LlmProvider provider : providerRepository.findAllByOrderByBuiltinDescCreatedAtAsc()) {
            boolean ready = Boolean.TRUE.equals(provider.getEnabled()) && hasKey(provider);
            for (String model : parseModels(provider)) {
                String key = model.toLowerCase(Locale.ROOT);
                if (!seen.add(key)) {
                    continue;
                }
                options.add(new GatewayModelOption(
                        model,
                        model + " · " + provider.getName(),
                        provider.getId(),
                        provider.getName(),
                        ready
                ));
            }
        }
        return options;
    }

    @Transactional
    public LlmProviderView create(LlmProviderRequest request) {
        LlmProviderType vendor = request.getVendor() != null ? request.getVendor() : LlmProviderType.CUSTOM;
        if (vendor != LlmProviderType.CUSTOM && providerRepository.countByVendor(vendor) > 0) {
            throw new IllegalArgumentException("该供应商通道已存在，请直接编辑现有配置");
        }
        LlmVendorCatalog.VendorPreset preset = LlmVendorCatalog.preset(vendor);
        LlmProvider provider = new LlmProvider();
        provider.setId("llm-" + UUID.randomUUID().toString().substring(0, 8));
        provider.setVendor(vendor);
        provider.setBuiltin(false);
        applyRequest(provider, request, preset, true);
        return toView(providerRepository.save(provider));
    }

    @Transactional
    public LlmProviderView update(String id, LlmProviderRequest request) {
        LlmProvider provider = providerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到该模型通道"));
        LlmVendorCatalog.VendorPreset preset = LlmVendorCatalog.preset(provider.getVendor());
        applyRequest(provider, request, preset, false);
        return toView(providerRepository.save(provider));
    }

    @Transactional
    public LlmProviderView toggleEnabled(String id, boolean enabled) {
        LlmProvider provider = providerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到该模型通道"));
        if (enabled && !hasKey(provider)) {
            throw new IllegalArgumentException("请先配置 API Key 后再启用通道");
        }
        provider.setEnabled(enabled);
        return toView(providerRepository.save(provider));
    }

    @Transactional
    public void delete(String id) {
        LlmProvider provider = providerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到该模型通道"));
        if (Boolean.TRUE.equals(provider.getBuiltin())) {
            throw new IllegalArgumentException("内置官方通道不可删除，可清空密钥后停用");
        }
        GatewayPolicy policy = ensurePolicy();
        if (id.equals(policy.getDefaultProviderId())) {
            policy.setDefaultProviderId(null);
        }
        if (id.equals(policy.getFallbackProviderId())) {
            policy.setFallbackProviderId(null);
        }
        policyRepository.save(policy);
        providerRepository.delete(provider);
    }

    @Transactional
    public LlmProviderView probe(String id) {
        GatewayProbeResult result = testConnection(probeRequestFromProvider(id));
        LlmProvider provider = providerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到该模型通道"));
        provider.setLastProbeStatus(result.isSuccess() ? "SUCCESS" : "FAILED");
        provider.setLastProbeMessage(result.getMessage());
        provider.setLastProbeAt(LocalDateTime.now());
        return toView(providerRepository.save(provider));
    }

    @Transactional
    public GatewayProbeResult testConnection(GatewayProbeRequest request) {
        String baseUrl = request.getBaseUrl();
        String apiKey = request.getApiKey();
        Integer timeout = request.getTimeoutMs();
        String providerId = blankToNull(request.getProviderId());

        if (providerId != null) {
            LlmProvider provider = providerRepository.findById(providerId)
                    .orElseThrow(() -> new IllegalArgumentException("未找到该模型通道"));
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = provider.getBaseUrl();
            }
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = decryptKey(provider);
            }
            if (timeout == null) {
                timeout = provider.getTimeoutMs();
            }
        }

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("请填写 Base URL");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("请填写 API Key 后再测试");
        }

        int timeoutMs = timeout != null ? timeout : 15000;
        OpenAiCompatibleClient.ProbeResult result = openAiClient.probe(baseUrl, apiKey, timeoutMs);

        if (providerId != null && result.success() && !result.models().isEmpty()) {
            providerRepository.findById(providerId).ifPresent(provider -> {
                provider.setModels(String.join(",", result.models()));
                if (provider.getDefaultModel() == null || provider.getDefaultModel().isBlank()
                        || result.models().stream().noneMatch(item -> item.equalsIgnoreCase(provider.getDefaultModel()))) {
                    provider.setDefaultModel(result.models().get(0));
                }
                provider.setLastProbeStatus("SUCCESS");
                provider.setLastProbeMessage(result.message());
                provider.setLastProbeAt(LocalDateTime.now());
                providerRepository.save(provider);
            });
        } else if (providerId != null && (request.getApiKey() == null || request.getApiKey().isBlank())) {
            providerRepository.findById(providerId).ifPresent(provider -> {
                provider.setLastProbeStatus(result.success() ? "SUCCESS" : "FAILED");
                provider.setLastProbeMessage(result.message());
                provider.setLastProbeAt(LocalDateTime.now());
                providerRepository.save(provider);
            });
        }

        return new GatewayProbeResult(result.success(), result.message(), result.models());
    }

    private GatewayProbeRequest probeRequestFromProvider(String id) {
        GatewayProbeRequest request = new GatewayProbeRequest();
        request.setProviderId(id);
        return request;
    }

    @Transactional
    public GatewayPolicy savePolicy(GatewayPolicy incoming) {
        GatewayPolicy policy = ensurePolicy();
        policy.setDefaultProviderId(blankToNull(incoming.getDefaultProviderId()));
        policy.setFallbackProviderId(blankToNull(incoming.getFallbackProviderId()));
        if (policy.getDefaultProviderId() != null && policy.getDefaultProviderId().equals(policy.getFallbackProviderId())) {
            throw new IllegalArgumentException("默认通道与降级通道不能相同");
        }
        if (incoming.getFailoverEnabled() != null) {
            policy.setFailoverEnabled(incoming.getFailoverEnabled());
        }
        if (incoming.getTimeoutMs() != null) {
            policy.setTimeoutMs(Math.max(3000, incoming.getTimeoutMs()));
        }
        if (incoming.getMaxRetries() != null) {
            policy.setMaxRetries(Math.max(0, Math.min(3, incoming.getMaxRetries())));
        }
        return policyRepository.save(policy);
    }

    @Transactional
    public Optional<ResolvedRoute> resolveRoute(String modelName) {
        List<LlmProvider> all = providerRepository.findAllByOrderByBuiltinDescCreatedAtAsc();
        GatewayPolicy policy = ensurePolicy();
        LlmProvider primary = findReady(all, policy.getDefaultProviderId());
        if (primary == null) {
            primary = findReadyByModel(all, modelName);
        }
        if (primary == null) {
            primary = all.stream().filter(this::isReady).findFirst().orElse(null);
        }
        if (primary == null) {
            return Optional.empty();
        }
        LlmProvider fallback = Boolean.TRUE.equals(policy.getFailoverEnabled())
                ? findReady(all, policy.getFallbackProviderId())
                : null;
        if (fallback != null && fallback.getId().equals(primary.getId())) {
            fallback = null;
        }
        int timeout = firstPositive(primary.getTimeoutMs(), policy.getTimeoutMs(), 30000);
        int retries = primary.getMaxRetries() != null
                ? primary.getMaxRetries()
                : (policy.getMaxRetries() != null ? policy.getMaxRetries() : 1);
        return Optional.of(new ResolvedRoute(primary, fallback, timeout, retries, decryptKey(primary),
                fallback != null ? decryptKey(fallback) : null));
    }

    public String decryptKey(LlmProvider provider) {
        if (provider == null || provider.getApiKeyEncrypted() == null) {
            return null;
        }
        return secretCrypto.decrypt(provider.getApiKeyEncrypted());
    }

    private void applyRequest(LlmProvider provider, LlmProviderRequest request,
                              LlmVendorCatalog.VendorPreset preset, boolean creating) {
        if (request.getName() != null && !request.getName().isBlank()) {
            provider.setName(request.getName().trim());
        } else if (creating) {
            provider.setName(preset.name());
        }
        if (request.getBaseUrl() != null && !request.getBaseUrl().isBlank()) {
            provider.setBaseUrl(OpenAiCompatibleClient.normalizeBase(request.getBaseUrl()));
        } else if (creating) {
            provider.setBaseUrl(preset.baseUrl());
        }
        if (request.getDefaultModel() != null && !request.getDefaultModel().isBlank()) {
            provider.setDefaultModel(request.getDefaultModel().trim());
        } else if (creating) {
            provider.setDefaultModel(preset.defaultModel());
        }
        if (request.getModels() != null) {
            provider.setModels(normalizeModelCsv(request.getModels()));
        } else if (creating) {
            provider.setModels(String.join(",", preset.models()));
        }
        if (request.getTimeoutMs() != null) {
            provider.setTimeoutMs(Math.max(3000, request.getTimeoutMs()));
        }
        if (request.getMaxRetries() != null) {
            provider.setMaxRetries(Math.max(0, Math.min(3, request.getMaxRetries())));
        }
        if (request.getRemark() != null) {
            provider.setRemark(request.getRemark().trim());
        }
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            provider.setApiKeyEncrypted(secretCrypto.encrypt(request.getApiKey().trim()));
            provider.setLastProbeStatus("UNTESTED");
            provider.setLastProbeMessage("密钥已更新，待探测");
            provider.setLastProbeAt(null);
        }
        if (request.getEnabled() != null) {
            if (Boolean.TRUE.equals(request.getEnabled()) && !hasKey(provider)) {
                throw new IllegalArgumentException("请先配置 API Key 后再启用通道");
            }
            provider.setEnabled(request.getEnabled());
        } else if (creating) {
            provider.setEnabled(false);
        }
    }

    private GatewayPolicy ensurePolicy() {
        return policyRepository.findById(POLICY_ID).orElseGet(() -> {
            GatewayPolicy policy = new GatewayPolicy();
            policy.setId(POLICY_ID);
            policy.setFailoverEnabled(true);
            policy.setTimeoutMs(30000);
            policy.setMaxRetries(1);
            policy.setDefaultProviderId("llm-deepseek");
            return policyRepository.save(policy);
        });
    }

    private LlmProviderView toView(LlmProvider provider) {
        LlmProviderView view = new LlmProviderView();
        view.setId(provider.getId());
        view.setVendor(provider.getVendor());
        view.setName(provider.getName());
        view.setBaseUrl(provider.getBaseUrl());
        view.setConfigured(hasKey(provider));
        view.setApiKeyMasked(maskKey(decryptQuietly(provider)));
        view.setDefaultModel(provider.getDefaultModel());
        view.setModels(provider.getModels());
        view.setModelList(parseModels(provider));
        view.setEnabled(Boolean.TRUE.equals(provider.getEnabled()));
        view.setBuiltin(Boolean.TRUE.equals(provider.getBuiltin()));
        view.setTimeoutMs(provider.getTimeoutMs());
        view.setMaxRetries(provider.getMaxRetries());
        view.setRemark(provider.getRemark());
        view.setLastProbeStatus(provider.getLastProbeStatus());
        view.setLastProbeMessage(provider.getLastProbeMessage());
        view.setLastProbeAt(provider.getLastProbeAt());
        view.setUpdatedAt(provider.getUpdatedAt());
        return view;
    }

    private String decryptQuietly(LlmProvider provider) {
        try {
            return decryptKey(provider);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasKey(LlmProvider provider) {
        return provider.getApiKeyEncrypted() != null && !provider.getApiKeyEncrypted().isBlank();
    }

    private boolean isReady(LlmProvider provider) {
        return Boolean.TRUE.equals(provider.getEnabled()) && hasKey(provider);
    }

    private LlmProvider findReady(List<LlmProvider> all, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return all.stream().filter(item -> id.equals(item.getId()) && isReady(item)).findFirst().orElse(null);
    }

    private LlmProvider findReadyByModel(List<LlmProvider> all, String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        String wanted = modelName.trim();
        return all.stream()
                .filter(this::isReady)
                .filter(item -> parseModels(item).stream().anyMatch(model -> model.equalsIgnoreCase(wanted)))
                .findFirst()
                .orElse(null);
    }

    private List<String> parseModels(LlmProvider provider) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        if (provider.getDefaultModel() != null && !provider.getDefaultModel().isBlank()) {
            models.add(provider.getDefaultModel().trim());
        }
        if (provider.getModels() != null && !provider.getModels().isBlank()) {
            Arrays.stream(provider.getModels().split("[,，\\n]"))
                    .map(String::trim)
                    .filter(item -> !item.isEmpty())
                    .forEach(models::add);
        }
        return new ArrayList<>(models);
    }

    private String normalizeModelCsv(String raw) {
        return Arrays.stream(raw.split("[,，\\n]"))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.joining(","));
    }

    private String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String value = key.trim();
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int firstPositive(Integer... values) {
        for (Integer value : values) {
            if (value != null && value > 0) {
                return value;
            }
        }
        return 30000;
    }

    public record ResolvedRoute(
            LlmProvider primary,
            LlmProvider fallback,
            int timeoutMs,
            int maxRetries,
            String primaryKey,
            String fallbackKey
    ) {
    }
}
