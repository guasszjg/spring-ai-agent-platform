package com.example.agentplatform.service;

import com.example.agentplatform.config.SecretCrypto;
import com.example.agentplatform.model.EmbeddingConfig;
import com.example.agentplatform.model.EmbeddingConfigRequest;
import com.example.agentplatform.model.EmbeddingConfigView;
import com.example.agentplatform.repository.EmbeddingConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class EmbeddingConfigService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfigService.class);

    private final EmbeddingConfigRepository repository;
    private final SecretCrypto secretCrypto;
    private final ObjectMapper objectMapper;

    public EmbeddingConfigService(EmbeddingConfigRepository repository,
                                  SecretCrypto secretCrypto,
                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.secretCrypto = secretCrypto;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public List<EmbeddingConfigView> listViews() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(e -> EmbeddingConfigView.from(e, decryptKey(e.getApiKeyEncrypted())))
                .collect(Collectors.toList());
    }

    public Optional<EmbeddingConfig> getActiveConfig() {
        Optional<EmbeddingConfig> active = repository.findFirstByIsActiveTrue();
        if (active.isPresent()) {
            return active;
        }
        // 如果没有标记为 active 的，但列表中有启用的项，自愈将第一项置为 active
        List<EmbeddingConfig> all = repository.findAllByOrderByCreatedAtDesc();
        if (!all.isEmpty()) {
            EmbeddingConfig first = all.get(0);
            first.setIsActive(true);
            repository.save(first);
            return Optional.of(first);
        }
        return Optional.empty();
    }

    public Optional<EmbeddingConfig> getById(String id) {
        return repository.findById(id);
    }

    @Transactional
    public EmbeddingConfigView create(EmbeddingConfigRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("配置名称不能为空");
        }
        if (req.getModelName() == null || req.getModelName().isBlank()) {
            throw new IllegalArgumentException("向量模型名称 (Model) 不能为空");
        }

        EmbeddingConfig entity = new EmbeddingConfig();
        entity.setName(req.getName().trim());
        entity.setProvider(req.getProvider() != null ? req.getProvider().trim().toUpperCase() : "OPENAI");
        entity.setBaseUrl(normalizeBaseUrl(req.getBaseUrl()));
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            entity.setApiKeyEncrypted(secretCrypto.encrypt(req.getApiKey().trim()));
        }
        entity.setModelName(req.getModelName().trim());
        entity.setDimension(req.getDimension() != null && req.getDimension() > 0 ? req.getDimension() : 1024);
        entity.setEnabled(req.getEnabled() == null || req.getEnabled());

        long count = repository.count();
        boolean makeActive = Boolean.TRUE.equals(req.getIsActive()) || count == 0;
        if (makeActive) {
            repository.deactivateAll();
            entity.setIsActive(true);
        } else {
            entity.setIsActive(false);
        }

        EmbeddingConfig saved = repository.save(entity);
        return EmbeddingConfigView.from(saved, decryptKey(saved.getApiKeyEncrypted()));
    }

    @Transactional
    public EmbeddingConfigView update(String id, EmbeddingConfigRequest req) {
        EmbeddingConfig entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到对应的 Embedding 配置: " + id));

        if (req.getName() != null && !req.getName().isBlank()) {
            entity.setName(req.getName().trim());
        }
        if (req.getProvider() != null && !req.getProvider().isBlank()) {
            entity.setProvider(req.getProvider().trim().toUpperCase());
        }
        if (req.getBaseUrl() != null) {
            entity.setBaseUrl(normalizeBaseUrl(req.getBaseUrl()));
        }
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            entity.setApiKeyEncrypted(secretCrypto.encrypt(req.getApiKey().trim()));
        }
        if (req.getModelName() != null && !req.getModelName().isBlank()) {
            entity.setModelName(req.getModelName().trim());
        }
        if (req.getDimension() != null && req.getDimension() > 0) {
            entity.setDimension(req.getDimension());
        }
        if (req.getEnabled() != null) {
            entity.setEnabled(req.getEnabled());
        }
        if (Boolean.TRUE.equals(req.getIsActive())) {
            repository.deactivateOthers(id);
            entity.setIsActive(true);
        }

        EmbeddingConfig saved = repository.save(entity);
        return EmbeddingConfigView.from(saved, decryptKey(saved.getApiKeyEncrypted()));
    }

    @Transactional
    public void activate(String id) {
        EmbeddingConfig entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到对应的 Embedding 配置: " + id));
        repository.deactivateOthers(id);
        entity.setIsActive(true);
        entity.setEnabled(true);
        repository.save(entity);
    }

    @Transactional
    public void delete(String id) {
        EmbeddingConfig entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到对应的 Embedding 配置: " + id));
        boolean wasActive = Boolean.TRUE.equals(entity.getIsActive());
        repository.delete(entity);
        if (wasActive) {
            // 自动将剩余的第一个置为 active
            repository.findAllByOrderByCreatedAtDesc().stream().findFirst().ifPresent(f -> {
                f.setIsActive(true);
                repository.save(f);
            });
        }
    }

    /**
     * 测试向量模型连通性 (Probe)
     */
    public Map<String, Object> probe(String id) {
        EmbeddingConfig entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到对应的 Embedding 配置: " + id));
        Map<String, Object> result = ping(entity.getBaseUrl(), decryptKey(entity.getApiKeyEncrypted()), entity.getModelName());
        boolean ok = Boolean.TRUE.equals(result.get("success"));
        entity.setLastProbeStatus(ok ? "SUCCESS" : "FAILED");
        entity.setLastProbeMessage(String.valueOf(result.get("message")));
        entity.setLastProbeAt(LocalDateTime.now());
        Object dim = result.get("dimension");
        if (ok && dim instanceof Integer detected && detected > 0) {
            entity.setDimension(detected);
        }
        repository.save(entity);
        return result;
    }

    public Map<String, Object> probeDraft(EmbeddingConfigRequest req) {
        if (req == null || req.getModelName() == null || req.getModelName().isBlank()) {
            throw new IllegalArgumentException("请先填写向量模型名称");
        }
        String apiKey = req.getApiKey() != null ? req.getApiKey().trim() : "";
        if (apiKey.isBlank() && req.getId() != null && !req.getId().isBlank()) {
            apiKey = repository.findById(req.getId())
                    .map(cfg -> decryptKey(cfg.getApiKeyEncrypted()))
                    .orElse("");
        }
        return ping(req.getBaseUrl(), apiKey, req.getModelName().trim());
    }

    private Map<String, Object> ping(String baseUrl, String plainKey, String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("向量模型名称不能为空");
        }
        long start = System.currentTimeMillis();
        try {
            String endpoint = baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://api.openai.com/v1";
            if (!endpoint.endsWith("/embeddings")) {
                while (endpoint.endsWith("/")) {
                    endpoint = endpoint.substring(0, endpoint.length() - 1);
                }
                endpoint = endpoint + "/embeddings";
            }

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(10));
            factory.setReadTimeout(Duration.ofSeconds(20));
            RestClient client = RestClient.builder().requestFactory(factory).build();

            var reqBody = Map.of(
                    "model", model,
                    "input", "Ping test embedding connection"
            );
            var reqSpec = client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON);
            if (plainKey != null && !plainKey.isBlank()) {
                reqSpec.header(HttpHeaders.AUTHORIZATION, "Bearer " + plainKey);
            }
            String respBody = reqSpec.body(reqBody).retrieve().body(String.class);
            long cost = System.currentTimeMillis() - start;

            JsonNode root = objectMapper.readTree(respBody);
            int detectedDim = 0;
            if (root.has("data") && root.get("data").isArray() && !root.get("data").isEmpty()) {
                JsonNode firstItem = root.get("data").get(0);
                if (firstItem.has("embedding") && firstItem.get("embedding").isArray()) {
                    detectedDim = firstItem.get("embedding").size();
                }
            }
            String msg = "连通正常！成功生成 " + (detectedDim > 0 ? detectedDim + " 维" : "") + "向量 (耗时 " + cost + "ms)";
            return Map.of("success", true, "message", msg, "dimension", detectedDim, "costMs", cost);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (err.contains("401")) {
                err = "401 Unauthorized (API Key 无效或未授权)";
            } else if (err.contains("404")) {
                err = "404 Not Found (接口地址不正确，未找到 /embeddings 端点)";
            }
            return Map.of("success", false, "message", "连通失败: " + err + " (耗时 " + cost + "ms)", "costMs", cost);
        }
    }

    public String decryptKey(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return "";
        try {
            return secretCrypto.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("解密 Embedding Key 异常: {}", e.getMessage());
            return "";
        }
    }

    private String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String url = raw.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
