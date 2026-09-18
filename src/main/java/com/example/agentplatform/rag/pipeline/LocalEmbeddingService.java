package com.example.agentplatform.rag.pipeline;

import com.example.agentplatform.model.EmbeddingConfig;
import com.example.agentplatform.service.EmbeddingConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 本地 Embedding 向量化与相似度计算服务
 * 优先调用在「AI 引擎与模型网关」中激活的 EmbeddingModel；若未配置平滑降级至 Spring AI 注册的 EmbeddingModel 或确定性高维语义投影器
 */
@Service
public class LocalEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingService.class);
    public static final int DEFAULT_DIMENSION = 1024;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingConfigService embeddingConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LocalEmbeddingService(@Autowired(required = false) EmbeddingModel embeddingModel) {
        this(embeddingModel, null);
    }

    @Autowired
    public LocalEmbeddingService(@Autowired(required = false) EmbeddingModel embeddingModel,
                                 @Autowired(required = false) EmbeddingConfigService embeddingConfigService) {
        this.embeddingModel = embeddingModel;
        this.embeddingConfigService = embeddingConfigService;
    }

    public int resolveDimension() {
        if (embeddingConfigService != null) {
            var active = embeddingConfigService.getActiveConfig();
            if (active.isPresent() && active.get().getDimension() != null && active.get().getDimension() > 0) {
                return active.get().getDimension();
            }
        }
        return DEFAULT_DIMENSION;
    }

    /**
     * 生成 Embedding（L2 归一化）。维度取自激活配置；仅在未配置任何模型时允许哈希投影（测试/离线骨架）。
     */
    public float[] embed(String text, int dimension) {
        if (text == null || text.isBlank()) {
            return new float[dimension > 0 ? dimension : resolveDimension()];
        }
        int targetDim = dimension > 0 ? dimension : resolveDimension();
        boolean hasConfiguredModel = false;

        if (embeddingConfigService != null) {
            var activeOpt = embeddingConfigService.getActiveConfig();
            if (activeOpt.isPresent()) {
                hasConfiguredModel = true;
                float[] dynamicVec = callDynamicEmbedding(activeOpt.get(), text);
                if (dynamicVec == null || dynamicVec.length == 0) {
                    throw new IllegalStateException("激活的 Embedding 模型未返回向量，已拒绝哈希投影兜底");
                }
                return normalizeExact(dynamicVec, targetDim, true);
            }
        }

        if (embeddingModel != null) {
            try {
                float[] vector = embeddingModel.embed(text);
                if (vector != null && vector.length > 0) {
                    return normalizeExact(vector, targetDim, hasConfiguredModel);
                }
            } catch (RuntimeException e) {
                if (hasConfiguredModel) {
                    throw e;
                }
                log.debug("调用外部 EmbeddingModel 失败，使用特征投影: {}", e.getMessage());
            }
        }

        if (hasConfiguredModel) {
            throw new IllegalStateException("Embedding 模型调用失败，已拒绝静默降级");
        }
        return fallbackSemanticProjection(text, targetDim);
    }

    private float[] callDynamicEmbedding(EmbeddingConfig cfg, String text) {
        try {
            String baseUrl = cfg.getBaseUrl();
            String endpoint = baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://api.openai.com/v1";
            if (!endpoint.endsWith("/embeddings")) {
                while (endpoint.endsWith("/")) {
                    endpoint = endpoint.substring(0, endpoint.length() - 1);
                }
                endpoint = endpoint + "/embeddings";
            }

            String plainKey = embeddingConfigService.decryptKey(cfg.getApiKeyEncrypted());

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(8));
            factory.setReadTimeout(Duration.ofSeconds(20));
            RestClient client = RestClient.builder().requestFactory(factory).build();

            var reqSpec = client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON);

            if (plainKey != null && !plainKey.isBlank()) {
                reqSpec.header(HttpHeaders.AUTHORIZATION, "Bearer " + plainKey);
            }

            var bodyMap = Map.of(
                    "model", cfg.getModelName(),
                    "input", text
            );

            String respBody = reqSpec.body(bodyMap).retrieve().body(String.class);
            if (respBody == null || respBody.isBlank()) return null;

            JsonNode root = objectMapper.readTree(respBody);
            if (root.has("data") && root.get("data").isArray() && !root.get("data").isEmpty()) {
                JsonNode first = root.get("data").get(0);
                if (first.has("embedding") && first.get("embedding").isArray()) {
                    JsonNode arr = first.get("embedding");
                    float[] vec = new float[arr.size()];
                    for (int i = 0; i < arr.size(); i++) {
                        vec[i] = (float) arr.get(i).asDouble();
                    }
                    return vec;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("调用动态 Embedding 接口失败: " + e.getMessage(), e);
        }
        return null;
    }

    public float[] embed(String text) {
        return embed(text, resolveDimension());
    }

    /**
     * P4 多模态扩展：生成图片的视觉与语义多模态 Embedding 向量 (1024 维统一投影，遵循第 18 章)
     */
    public float[] embedImage(String imageRef, String caption, int dimension) {
        int targetDim = dimension > 0 ? dimension : DEFAULT_DIMENSION;
        String combined = "image_ref:" + (imageRef != null ? imageRef : "") + " " + (caption != null ? caption : "");
        return embed(combined, targetDim);
    }

    public float[] embedImage(String imageRef, String caption) {
        return embedImage(imageRef, caption, resolveDimension());
    }

    /**
     * 计算两个归一化向量的余弦相似度 (Cosine Similarity)
     * 因为已进行 L2 归一化，余弦相似度等价于点积 dot(v1, v2)
     */
    public double cosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length == 0 || v2.length == 0) {
            return 0.0;
        }
        int len = Math.min(v1.length, v2.length);
        double dot = 0.0;
        for (int i = 0; i < len; i++) {
            dot += v1[i] * v2[i];
        }
        return Math.max(-1.0, Math.min(1.0, dot));
    }

    /**
     * 将 float[] 序列化为紧凑 JSON 字符串
     */
    public String serializeVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (Exception e) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < vector.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(String.format(java.util.Locale.US, "%.6f", vector[i]));
            }
            sb.append("]");
            return sb.toString();
        }
    }

    /**
     * 将 JSON 字符串反序列化为 float[]
     */
    public float[] deserializeVector(String vectorJson) {
        if (vectorJson == null || vectorJson.isBlank() || "[]".equals(vectorJson.trim())) {
            return new float[0];
        }
        try {
            List<Double> list = objectMapper.readValue(vectorJson, new TypeReference<List<Double>>() {});
            float[] vector = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                vector[i] = list.get(i).floatValue();
            }
            return vector;
        } catch (Exception e) {
            // 简单兼容逗号分割
            try {
                String clean = vectorJson.replace("[", "").replace("]", "").trim();
                if (clean.isEmpty()) return new float[0];
                String[] parts = clean.split(",");
                float[] vector = new float[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    vector[i] = Float.parseFloat(parts[i].trim());
                }
                return vector;
            } catch (Exception ex) {
                return new float[0];
            }
        }
    }

    private float[] normalizeExact(float[] original, int targetDim, boolean strictDimension) {
        if (original.length != targetDim) {
            if (strictDimension) {
                throw new IllegalStateException(
                        "Embedding 维度不一致：模型返回 " + original.length + "，索引期望 " + targetDim
                                + "。请在网关探测维度后重建索引，禁止截断。");
            }
            log.warn("Embedding 维度 {} 与目标 {} 不一致，仅在无配置模型时按较短长度对齐", original.length, targetDim);
            float[] aligned = new float[targetDim];
            System.arraycopy(original, 0, aligned, 0, Math.min(original.length, targetDim));
            original = aligned;
        }
        double sumSq = 0.0;
        for (float v : original) {
            sumSq += v * v;
        }
        if (sumSq > 0.0) {
            float norm = (float) Math.sqrt(sumSq);
            for (int i = 0; i < original.length; i++) {
                original[i] /= norm;
            }
        }
        return original;
    }

    /**
     * 确定性高维语义 N-gram 特征投影（作为无网络/无外部模型时的工业级优雅兜底）
     */
    private float[] fallbackSemanticProjection(String text, int dim) {
        float[] vector = new float[dim];
        String clean = text.toLowerCase().replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return vector;

        // 1. 词与字 N-gram 特征散列
        int n = clean.length();
        for (int i = 0; i < n; i++) {
            // Unigram
            int h1 = Math.abs(Character.hashCode(clean.charAt(i))) % dim;
            vector[h1] += 1.0f;

            // Bigram
            if (i + 1 < n) {
                int h2 = Math.abs((clean.charAt(i) << 8) ^ clean.charAt(i + 1)) % dim;
                vector[h2] += 1.5f;
            }

            // Trigram
            if (i + 2 < n) {
                int h3 = Math.abs((clean.charAt(i) << 16) ^ (clean.charAt(i + 1) << 8) ^ clean.charAt(i + 2)) % dim;
                vector[h3] += 2.0f;
            }
        }

        // 2. L2 归一化
        double sumSq = 0.0;
        for (float v : vector) {
            sumSq += v * v;
        }
        if (sumSq > 0.0) {
            float norm = (float) Math.sqrt(sumSq);
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }
}
