package com.example.agentplatform.rag.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * 本地 Embedding 向量化与相似度计算服务
 * 优先调用 Spring AI 注册的 EmbeddingModel；若未配置或异常时平滑降级至确定性高维语义投影器
 */
@Service
public class LocalEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingService.class);
    public static final int DEFAULT_DIMENSION = 1024;

    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LocalEmbeddingService(@Autowired(required = false) EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 生成文本的 1024 维 Embedding 稠密向量 (已完成 L2 归一化)
     */
    public float[] embed(String text, int dimension) {
        if (text == null || text.isBlank()) {
            return new float[dimension > 0 ? dimension : DEFAULT_DIMENSION];
        }
        int targetDim = dimension > 0 ? dimension : DEFAULT_DIMENSION;

        if (embeddingModel != null) {
            try {
                float[] vector = embeddingModel.embed(text);
                if (vector != null && vector.length > 0) {
                    return normalizeAndProject(vector, targetDim);
                }
            } catch (Exception e) {
                log.debug("调用外部 EmbeddingModel 失败，平滑降级至平台内置语义特征投影: {}", e.getMessage());
            }
        }

        return fallbackSemanticProjection(text, targetDim);
    }

    public float[] embed(String text) {
        return embed(text, DEFAULT_DIMENSION);
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
        // 约束到 0.0 ~ 1.0 区间
        return Math.max(0.0, Math.min(1.0, (dot + 1.0) / 2.0));
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

    private float[] normalizeAndProject(float[] original, int targetDim) {
        float[] res = new float[targetDim];
        int copyLen = Math.min(original.length, targetDim);
        System.arraycopy(original, 0, res, 0, copyLen);

        double sumSq = 0.0;
        for (float v : res) {
            sumSq += v * v;
        }
        if (sumSq > 0.0) {
            float norm = (float) Math.sqrt(sumSq);
            for (int i = 0; i < res.length; i++) {
                res[i] /= norm;
            }
        }
        return res;
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
