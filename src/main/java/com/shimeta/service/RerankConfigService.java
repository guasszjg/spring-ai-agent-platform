package com.shimeta.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.config.SecretCrypto;
import com.shimeta.model.RerankConfig;
import com.shimeta.model.RerankConfigRequest;
import com.shimeta.model.RerankConfigView;
import com.shimeta.rag.cache.SemanticCacheService;
import com.shimeta.repository.RerankConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 模型网关的 Rerank 模型：配置管理与接口调用。用哪个模型完全由当前生效的配置决定。
 */
@Service
public class RerankConfigService {

    private static final Logger log = LoggerFactory.getLogger(RerankConfigService.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(8);

    private final RerankConfigRepository repository;
    private final SecretCrypto secretCrypto;
    private final ObjectMapper objectMapper;
    private final SemanticCacheService semanticCacheService;

    public record Hit(int index, double score) {}

    /** @param totalTokens 接口返回的用量；接口没有返回时为 0 */
    public record Result(List<Hit> hits, long totalTokens) {}

    @Autowired
    public RerankConfigService(RerankConfigRepository repository,
                               SecretCrypto secretCrypto,
                               ObjectMapper objectMapper,
                               @Autowired(required = false) SemanticCacheService semanticCacheService) {
        this.repository = repository;
        this.secretCrypto = secretCrypto;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.semanticCacheService = semanticCacheService;
    }

    public List<RerankConfigView> listViews() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(this::toView).toList();
    }

    public Optional<RerankConfig> getActiveConfig() {
        return repository.findFirstByIsActiveTrue();
    }

    @Transactional
    public RerankConfigView create(RerankConfigRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("配置名称不能为空");
        }
        requireEndpointAndModel(req.getBaseUrl(), req.getModelName());

        RerankConfig entity = new RerankConfig();
        entity.setName(req.getName().trim());
        entity.setBaseUrl(normalizeBaseUrl(req.getBaseUrl()));
        entity.setModelName(req.getModelName().trim());
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            entity.setApiKeyEncrypted(secretCrypto.encrypt(req.getApiKey().trim()));
        }
        if (req.getCustomHeaders() != null) {
            entity.setCustomHeadersEncrypted(encryptHeaders(EmbeddingConfigService.mergeHeaders(req.getCustomHeaders(), Map.of())));
        }
        boolean makeActive = Boolean.TRUE.equals(req.getIsActive()) || repository.count() == 0;
        if (makeActive) {
            repository.deactivateAll();
            clearRetrievalCache();
        }
        entity.setIsActive(makeActive);
        return toView(repository.save(entity));
    }

    @Transactional
    public RerankConfigView update(String id, RerankConfigRequest req) {
        RerankConfig entity = find(id);
        if (req.getName() != null && !req.getName().isBlank()) {
            entity.setName(req.getName().trim());
        }
        if (req.getBaseUrl() != null && !req.getBaseUrl().isBlank()) {
            entity.setBaseUrl(normalizeBaseUrl(req.getBaseUrl()));
        }
        if (req.getModelName() != null && !req.getModelName().isBlank()) {
            entity.setModelName(req.getModelName().trim());
        }
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            entity.setApiKeyEncrypted(secretCrypto.encrypt(req.getApiKey().trim()));
        }
        if (req.getCustomHeaders() != null) {
            entity.setCustomHeadersEncrypted(encryptHeaders(
                    EmbeddingConfigService.mergeHeaders(req.getCustomHeaders(), decryptHeaders(entity))));
        }
        if (Boolean.TRUE.equals(req.getIsActive())) {
            repository.deactivateOthers(id);
            entity.setIsActive(true);
        }
        if (Boolean.TRUE.equals(entity.getIsActive())) {
            clearRetrievalCache();
        }
        return toView(repository.save(entity));
    }

    @Transactional
    public void activate(String id) {
        RerankConfig entity = find(id);
        repository.deactivateOthers(id);
        entity.setIsActive(true);
        repository.save(entity);
        clearRetrievalCache();
    }

    /** 删除生效中的配置后不自动启用其他配置：Rerank 按量计费，换模型应由管理员决定 */
    @Transactional
    public void delete(String id) {
        RerankConfig entity = find(id);
        boolean wasActive = Boolean.TRUE.equals(entity.getIsActive());
        repository.delete(entity);
        if (wasActive) {
            clearRetrievalCache();
        }
    }

    public Map<String, Object> probe(String id) {
        RerankConfig entity = find(id);
        Map<String, Object> result = ping(entity.getBaseUrl(), decryptKey(entity), decryptHeaders(entity), entity.getModelName());
        entity.setLastProbeStatus(Boolean.TRUE.equals(result.get("success")) ? "SUCCESS" : "FAILED");
        String message = String.valueOf(result.get("message"));
        entity.setLastProbeMessage(message.length() > 500 ? message.substring(0, 500) : message);
        entity.setLastProbeAt(LocalDateTime.now());
        repository.save(entity);
        return result;
    }

    public Map<String, Object> probeDraft(RerankConfigRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("请先填写接口地址和模型名称");
        }
        requireEndpointAndModel(req.getBaseUrl(), req.getModelName());
        RerankConfig saved = req.getId() != null && !req.getId().isBlank()
                ? repository.findById(req.getId()).orElse(null) : null;
        String apiKey = req.getApiKey() != null ? req.getApiKey().trim() : "";
        if (apiKey.isBlank() && saved != null) {
            apiKey = decryptKey(saved);
        }
        Map<String, String> savedHeaders = saved != null ? decryptHeaders(saved) : Map.of();
        Map<String, String> headers = req.getCustomHeaders() != null
                ? EmbeddingConfigService.mergeHeaders(req.getCustomHeaders(), savedHeaders) : savedHeaders;
        return ping(req.getBaseUrl(), apiKey, headers, req.getModelName().trim());
    }

    private Map<String, Object> ping(String baseUrl, String plainKey, Map<String, String> headers, String model) {
        long start = System.currentTimeMillis();
        try {
            Result result = request(baseUrl, plainKey, headers, model, "苹果是什么",
                    List.of("苹果是一种常见的水果", "今天的天气不错"), Duration.ofSeconds(10), Duration.ofSeconds(20));
            long cost = System.currentTimeMillis() - start;
            return Map.of("success", true, "message", "连通正常！返回 " + result.hits().size() + " 条重排结果 (耗时 " + cost + "ms)", "costMs", cost);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (err.contains("401")) {
                err = "401 Unauthorized (API Key 无效或未授权)";
            } else if (err.contains("404")) {
                err = "404 Not Found (接口地址不正确：" + resolveEndpoint(baseUrl) + ")";
            }
            return Map.of("success", false, "message", "连通失败: " + err + " (耗时 " + cost + "ms)", "costMs", cost);
        }
    }

    /** 用指定配置重排 documents，结果按得分从高到低排列；调用失败时抛出异常，由调用方决定如何降级 */
    public Result rerank(RerankConfig config, String query, List<String> documents) {
        return request(config.getBaseUrl(), decryptKey(config), decryptHeaders(config), config.getModelName(),
                query, documents, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    private Result request(String baseUrl, String plainKey, Map<String, String> headers, String model,
                           String query, List<String> documents, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        RestClient client = RestClient.builder().requestFactory(factory).build();

        var spec = client.post().uri(resolveEndpoint(baseUrl)).contentType(MediaType.APPLICATION_JSON);
        if (plainKey != null && !plainKey.isBlank()) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + plainKey);
        }
        if (headers != null) {
            headers.forEach(spec::header);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", documents.size());
        // 先序列化成字节，让请求带 Content-Length：有些网关不接受分块发送
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new IllegalStateException("Rerank 请求序列化失败", e);
        }
        return parseResponse(spec.body(payload).retrieve().body(String.class), documents.size());
    }

    /** 解析 {"results":[{"index":0,"relevance_score":0.9}],"usage":{"total_tokens":10}}，也接受 data、output.results 和 score 这些常见写法 */
    Result parseResponse(String respBody, int documentCount) {
        if (respBody == null || respBody.isBlank()) {
            throw new IllegalStateException("Rerank 接口返回为空");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(respBody);
        } catch (Exception e) {
            throw new IllegalStateException("Rerank 接口返回的不是 JSON: " + EmbeddingConfigService.abbreviate(respBody));
        }
        JsonNode items = root.path("results");
        if (!items.isArray()) {
            items = root.path("data");
        }
        if (!items.isArray()) {
            items = root.path("output").path("results");
        }
        if (!items.isArray() || items.isEmpty()) {
            throw new IllegalStateException("Rerank 接口返回错误: " + EmbeddingConfigService.businessError(root));
        }
        List<Hit> hits = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            JsonNode score = item.hasNonNull("relevance_score") ? item.get("relevance_score") : item.path("score");
            int index = item.path("index").asInt(-1);
            if (index >= 0 && index < documentCount && score.isNumber()) {
                hits.add(new Hit(index, score.asDouble()));
            }
        }
        if (hits.isEmpty()) {
            throw new IllegalStateException("Rerank 接口返回的结果中没有 index 和 relevance_score");
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return new Result(hits, root.path("usage").path("total_tokens").asLong(0));
    }

    /**
     * 实际请求的接口地址：地址最后一段以 rerank 或 reranks 结尾（不区分大小写）时视为完整接口地址，原样使用；
     * 否则视为 Base URL，自动拼接 /rerank。
     */
    public static String resolveEndpoint(String baseUrl) {
        String endpoint = normalizeBaseUrl(baseUrl);
        String path = endpoint;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        String last = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return last.endsWith("rerank") || last.endsWith("reranks") ? endpoint : endpoint + "/rerank";
    }

    private void clearRetrievalCache() {
        if (semanticCacheService != null) {
            semanticCacheService.clearAll();
        }
    }

    private RerankConfig find(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到对应的 Rerank 配置: " + id));
    }

    private static void requireEndpointAndModel(String baseUrl, String model) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("接口地址不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Rerank 模型名称不能为空");
        }
    }

    private String decryptKey(RerankConfig entity) {
        String encrypted = entity.getApiKeyEncrypted();
        if (encrypted == null || encrypted.isBlank()) {
            return "";
        }
        try {
            return secretCrypto.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("解密 Rerank Key 异常: {}", e.getMessage());
            return "";
        }
    }

    private Map<String, String> decryptHeaders(RerankConfig entity) {
        String encrypted = entity.getCustomHeadersEncrypted();
        if (encrypted == null || encrypted.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> headers = objectMapper.readValue(secretCrypto.decrypt(encrypted),
                    new TypeReference<LinkedHashMap<String, String>>() {});
            return headers == null ? Map.of() : headers;
        } catch (Exception e) {
            log.warn("解密 Rerank 自定义请求头异常: {}", e.getMessage());
            return Map.of();
        }
    }

    private String encryptHeaders(Map<String, String> headers) {
        if (headers.isEmpty()) {
            return null;
        }
        try {
            return secretCrypto.encrypt(objectMapper.writeValueAsString(headers));
        } catch (Exception e) {
            throw new IllegalStateException("自定义请求头保存失败");
        }
    }

    private RerankConfigView toView(RerankConfig entity) {
        return RerankConfigView.from(entity, decryptKey(entity), decryptHeaders(entity).keySet());
    }

    private static String normalizeBaseUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
