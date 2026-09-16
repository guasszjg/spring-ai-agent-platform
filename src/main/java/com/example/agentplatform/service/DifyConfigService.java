package com.example.agentplatform.service;

import com.example.agentplatform.config.SecretCrypto;
import com.example.agentplatform.model.DifyConfig;
import com.example.agentplatform.model.DifyConfigRequest;
import com.example.agentplatform.model.DifyConfigView;
import com.example.agentplatform.repository.DifyConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
public class DifyConfigService {

    private static final Logger log = LoggerFactory.getLogger(DifyConfigService.class);

    private final DifyConfigRepository repository;
    private final SecretCrypto secretCrypto;
    private final ObjectMapper objectMapper;

    public DifyConfigService(DifyConfigRepository repository,
                             SecretCrypto secretCrypto,
                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.secretCrypto = secretCrypto;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public List<DifyConfigView> listViews() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(d -> DifyConfigView.from(d, decryptKey(d.getApiKeyEncrypted())))
                .collect(Collectors.toList());
    }

    public Optional<DifyConfig> getActiveConfig() {
        Optional<DifyConfig> active = repository.findFirstByIsActiveTrue();
        if (active.isPresent()) {
            return active;
        }
        // 如果没有标记为 active 的，但列表中有项，自愈将第一项置为 active
        List<DifyConfig> all = repository.findAllByOrderByCreatedAtDesc();
        if (!all.isEmpty()) {
            DifyConfig first = all.get(0);
            first.setIsActive(true);
            repository.save(first);
            return Optional.of(first);
        }
        return Optional.empty();
    }

    public Optional<DifyConfig> getById(String id) {
        return repository.findById(id);
    }

    @Transactional
    public DifyConfigView create(DifyConfigRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("Dify 实例名称不能为空");
        }
        if (req.getBaseUrl() == null || req.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("Dify 服务器 Base URL 不能为空");
        }
        if (req.getApiKey() == null || req.getApiKey().isBlank()) {
            throw new IllegalArgumentException("Dify Dataset API Key 不能为空");
        }

        DifyConfig entity = new DifyConfig();
        entity.setName(req.getName().trim());
        entity.setBaseUrl(normalizeDifyBaseUrl(req.getBaseUrl()));
        entity.setApiKeyEncrypted(secretCrypto.encrypt(req.getApiKey().trim()));
        entity.setDescription(req.getDescription() != null ? req.getDescription().trim() : "");
        entity.setEnabled(req.getEnabled() == null || req.getEnabled());

        long count = repository.count();
        boolean makeActive = Boolean.TRUE.equals(req.getIsActive()) || count == 0;
        if (makeActive) {
            repository.deactivateAll();
            entity.setIsActive(true);
        } else {
            entity.setIsActive(false);
        }

        DifyConfig saved = repository.save(entity);
        return DifyConfigView.from(saved, decryptKey(saved.getApiKeyEncrypted()));
    }

    @Transactional
    public DifyConfigView update(String id, DifyConfigRequest req) {
        DifyConfig entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到对应的 Dify 配置: " + id));

        if (req.getName() != null && !req.getName().isBlank()) {
            entity.setName(req.getName().trim());
        }
        if (req.getBaseUrl() != null && !req.getBaseUrl().isBlank()) {
            entity.setBaseUrl(normalizeDifyBaseUrl(req.getBaseUrl()));
        }
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            entity.setApiKeyEncrypted(secretCrypto.encrypt(req.getApiKey().trim()));
        }
        if (req.getDescription() != null) {
            entity.setDescription(req.getDescription().trim());
        }
        if (req.getEnabled() != null) {
            entity.setEnabled(req.getEnabled());
        }
        if (Boolean.TRUE.equals(req.getIsActive())) {
            repository.deactivateOthers(id);
            entity.setIsActive(true);
        }

        DifyConfig saved = repository.save(entity);
        return DifyConfigView.from(saved, decryptKey(saved.getApiKeyEncrypted()));
    }

    @Transactional
    public void activate(String id) {
        DifyConfig entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到对应的 Dify 配置: " + id));
        repository.deactivateOthers(id);
        entity.setIsActive(true);
        entity.setEnabled(true);
        repository.save(entity);
    }

    @Transactional
    public void delete(String id) {
        DifyConfig entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到对应的 Dify 配置: " + id));
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
     * 测试 Dify 连通性 (Probe)
     */
    public Map<String, Object> probe(String id) {
        DifyConfig entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到对应的 Dify 配置: " + id));

        String plainKey = decryptKey(entity.getApiKeyEncrypted());
        String baseUrl = entity.getBaseUrl();

        long start = System.currentTimeMillis();
        try {
            String endpoint = baseUrl;
            if (!endpoint.endsWith("/datasets")) {
                while (endpoint.endsWith("/")) {
                    endpoint = endpoint.substring(0, endpoint.length() - 1);
                }
                endpoint = endpoint + "/datasets?page=1&limit=1";
            }

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(10));
            factory.setReadTimeout(Duration.ofSeconds(20));

            RestClient client = RestClient.builder().requestFactory(factory).build();

            String respBody = client.get()
                    .uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + plainKey)
                    .retrieve()
                    .body(String.class);

            long cost = System.currentTimeMillis() - start;

            int datasetCount = 0;
            JsonNode root = objectMapper.readTree(respBody);
            if (root.has("total")) {
                datasetCount = root.get("total").asInt(0);
            } else if (root.has("data") && root.get("data").isArray()) {
                datasetCount = root.get("data").size();
            }

            String msg = "连通正常！Dify 服务响应成功 (检测到 " + datasetCount + " 个远程知识库，耗时 " + cost + "ms)";
            entity.setLastProbeStatus("SUCCESS");
            entity.setLastProbeMessage(msg);
            entity.setLastProbeAt(LocalDateTime.now());
            repository.save(entity);

            return Map.of("success", true, "message", msg, "datasetCount", datasetCount, "costMs", cost);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (err.contains("401") || err.contains("Unauthorized")) {
                err = "401 Unauthorized (Dify API Key 无效或未授权)";
            } else if (err.contains("404")) {
                err = "404 Not Found (接口路径错误，请确认是否为 Dify /v1 接口地址)";
            }
            String msg = "连通失败: " + err + " (耗时 " + cost + "ms)";
            entity.setLastProbeStatus("FAILED");
            entity.setLastProbeMessage(msg);
            entity.setLastProbeAt(LocalDateTime.now());
            repository.save(entity);

            return Map.of("success", false, "message", msg, "costMs", cost);
        }
    }

    public String decryptKey(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return "";
        try {
            return secretCrypto.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("解密 Dify API Key 异常: {}", e.getMessage());
            return "";
        }
    }

    public static String normalizeDifyBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String url = raw.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.endsWith("/v1")) {
            url = url + "/v1";
        }
        return url;
    }
}
