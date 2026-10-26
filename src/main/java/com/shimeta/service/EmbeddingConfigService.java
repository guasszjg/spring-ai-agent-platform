package com.shimeta.service;

import com.shimeta.config.SecretCrypto;
import com.shimeta.model.EmbeddingConfig;
import com.shimeta.model.EmbeddingConfigRequest;
import com.shimeta.model.EmbeddingConfigView;
import com.shimeta.repository.EmbeddingConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class EmbeddingConfigService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfigService.class);

    static final int MAX_CUSTOM_HEADERS = 10;
    static final int MAX_HEADER_VALUE = 500;
    /** RFC 7230 token 字符 */
    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}$");
    /** 由 HTTP 客户端自行设置、不允许自定义的请求头 */
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "host", "content-length", "content-type", "transfer-encoding", "connection", "expect", "upgrade");

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
                .map(this::toView)
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
        if (req.getCustomHeaders() != null) {
            entity.setCustomHeadersEncrypted(encryptHeaders(mergeHeaders(req.getCustomHeaders(), Map.of())));
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

        return toView(repository.save(entity));
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
        if (req.getCustomHeaders() != null) {
            entity.setCustomHeadersEncrypted(encryptHeaders(mergeHeaders(req.getCustomHeaders(), decryptHeaders(entity))));
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

        return toView(repository.save(entity));
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
        Map<String, Object> result = ping(entity.getBaseUrl(), decryptKey(entity.getApiKeyEncrypted()),
                decryptHeaders(entity), entity.getModelName());
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
        EmbeddingConfig saved = req.getId() != null && !req.getId().isBlank()
                ? repository.findById(req.getId()).orElse(null) : null;
        String apiKey = req.getApiKey() != null ? req.getApiKey().trim() : "";
        if (apiKey.isBlank() && saved != null) {
            apiKey = decryptKey(saved.getApiKeyEncrypted());
        }
        Map<String, String> savedHeaders = saved != null ? decryptHeaders(saved) : Map.of();
        Map<String, String> headers = req.getCustomHeaders() != null
                ? mergeHeaders(req.getCustomHeaders(), savedHeaders) : savedHeaders;
        return ping(req.getBaseUrl(), apiKey, headers, req.getModelName().trim());
    }

    private Map<String, Object> ping(String baseUrl, String plainKey, Map<String, String> headers, String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("向量模型名称不能为空");
        }
        long start = System.currentTimeMillis();
        try {
            List<float[]> vectors = requestEmbeddings(baseUrl, plainKey, headers, model,
                    List.of("Ping test embedding connection"), Duration.ofSeconds(10), Duration.ofSeconds(20));
            long cost = System.currentTimeMillis() - start;
            int detectedDim = vectors.isEmpty() ? 0 : vectors.get(0).length;
            String msg = "连通正常！成功生成 " + (detectedDim > 0 ? detectedDim + " 维" : "") + "向量 (耗时 " + cost + "ms)";
            return Map.of("success", true, "message", msg, "dimension", detectedDim, "costMs", cost);
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

    // ==================== 向量接口调用 ====================

    /**
     * 实际请求的接口地址：地址最后一段以 embeddings 结尾（不区分大小写，如 /v1/embeddings、/api/modelConfig/textEmbeddings）
     * 时视为完整接口地址，原样使用；否则视为 OpenAI 兼容的 Base URL，自动拼接 /embeddings。
     */
    public static String resolveEndpoint(String baseUrl) {
        String endpoint = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : "https://api.openai.com/v1";
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        String path = endpoint;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        String lastSegment = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return lastSegment.endsWith("embeddings") ? endpoint : endpoint + "/embeddings";
    }

    /**
     * 调用 OpenAI 兼容的向量接口。input 一律以字符串数组发送（OpenAI 标准同时支持字符串与数组，部分中转服务只接受数组）；
     * 不发送 encoding_format，部分厂商不认识该参数，默认即返回浮点数组。
     * 返回与 input 一一对应的向量；接口以 HTTP 200 返回业务错误（无 data，带 code / msg）时抛出异常。
     */
    public List<float[]> requestEmbeddings(String baseUrl, String plainKey, Map<String, String> headers, String model,
                                           List<String> inputs, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        RestClient client = RestClient.builder().requestFactory(factory).build();

        var reqSpec = client.post()
                .uri(resolveEndpoint(baseUrl))
                .contentType(MediaType.APPLICATION_JSON);
        if (plainKey != null && !plainKey.isBlank()) {
            reqSpec.header(HttpHeaders.AUTHORIZATION, "Bearer " + plainKey);
        }
        if (headers != null) {
            headers.forEach(reqSpec::header);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", inputs);

        String respBody = reqSpec.body(body).retrieve().body(String.class);
        if (respBody == null || respBody.isBlank()) {
            throw new IllegalStateException("向量接口返回为空");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(respBody);
        } catch (Exception e) {
            throw new IllegalStateException("向量接口返回的不是 JSON: " + abbreviate(respBody));
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("向量接口返回错误: " + businessError(root));
        }
        List<float[]> vectors = new ArrayList<>(data.size());
        for (JsonNode item : data) {
            JsonNode arr = item.path("embedding");
            if (!arr.isArray() || arr.isEmpty()) {
                throw new IllegalStateException("向量接口返回的数据中没有 embedding 数组");
            }
            float[] vec = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                vec[i] = (float) arr.get(i).asDouble();
            }
            vectors.add(vec);
        }
        return vectors;
    }

    /** 从常见的错误结构中取出说明：{"code":201,"msg":"..."}、{"message":"..."}、{"error":{"message":"..."}} */
    private static String businessError(JsonNode root) {
        List<String> parts = new ArrayList<>();
        if (root.hasNonNull("code")) {
            parts.add("code=" + root.get("code").asText());
        }
        for (String field : List.of("msg", "message")) {
            if (root.hasNonNull(field) && root.get(field).isValueNode()) {
                parts.add(root.get(field).asText());
            }
        }
        JsonNode error = root.path("error");
        if (error.isTextual()) {
            parts.add(error.asText());
        } else if (error.hasNonNull("message")) {
            parts.add(error.get("message").asText());
        }
        return parts.isEmpty() ? abbreviate(root.toString()) : abbreviate(String.join(" ", parts));
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    // ==================== 密钥与自定义请求头 ====================

    public String decryptKey(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return "";
        try {
            return secretCrypto.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("解密 Embedding Key 异常: {}", e.getMessage());
            return "";
        }
    }

    /** 已保存的自定义请求头（名称 → 值）；没有或解密失败时返回空 */
    public Map<String, String> decryptHeaders(EmbeddingConfig entity) {
        String encrypted = entity == null ? null : entity.getCustomHeadersEncrypted();
        if (encrypted == null || encrypted.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> headers = objectMapper.readValue(secretCrypto.decrypt(encrypted),
                    new TypeReference<LinkedHashMap<String, String>>() {});
            return headers == null ? Map.of() : headers;
        } catch (Exception e) {
            log.warn("解密 Embedding 自定义请求头异常: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 校验并合并请求头：按传入列表整体替换；值为空的项沿用已保存的同名请求头的值，已保存中没有的则视为未填写而报错。
     */
    static Map<String, String> mergeHeaders(List<EmbeddingConfigRequest.HeaderEntry> entries, Map<String, String> saved) {
        Map<String, String> result = new LinkedHashMap<>();
        for (EmbeddingConfigRequest.HeaderEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            String name = entry.getName() == null ? "" : entry.getName().trim();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (name.isEmpty() && value.isEmpty()) {
                continue;
            }
            if (!HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("请求头名称不合法：「" + name + "」（只能包含字母、数字和 - _ . 等符号，最长 64 个字符）");
            }
            if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("请求头「" + name + "」由系统自动设置，不能自定义");
            }
            if (result.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(name))) {
                throw new IllegalArgumentException("请求头「" + name + "」重复");
            }
            if (value.isEmpty()) {
                value = saved.entrySet().stream()
                        .filter(e -> e.getKey().equalsIgnoreCase(name))
                        .map(Map.Entry::getValue).findFirst().orElse("");
                if (value.isEmpty()) {
                    throw new IllegalArgumentException("请填写请求头「" + name + "」的值");
                }
            }
            if (value.length() > MAX_HEADER_VALUE || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("请求头「" + name + "」的值不合法（不能换行，最长 " + MAX_HEADER_VALUE + " 个字符）");
            }
            result.put(name, value);
        }
        if (result.size() > MAX_CUSTOM_HEADERS) {
            throw new IllegalArgumentException("自定义请求头最多 " + MAX_CUSTOM_HEADERS + " 个");
        }
        return result;
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

    private EmbeddingConfigView toView(EmbeddingConfig entity) {
        return EmbeddingConfigView.from(entity, decryptKey(entity.getApiKeyEncrypted()), decryptHeaders(entity).keySet());
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
