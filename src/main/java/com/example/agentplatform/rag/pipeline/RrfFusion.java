package com.example.agentplatform.rag.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion。对分数尺度不敏感，适合向量路与关键词路合并。
 */
public final class RrfFusion {

    public static final int DEFAULT_K = 60;

    private RrfFusion() {
    }

    public static List<String> fuse(List<String> vectorIds, List<String> keywordIds, int rrfK) {
        int k = rrfK > 0 ? rrfK : DEFAULT_K;
        Map<String, Double> scores = new LinkedHashMap<>();
        addRanks(scores, vectorIds, k);
        addRanks(scores, keywordIds, k);
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> ids = new ArrayList<>(ranked.size());
        for (Map.Entry<String, Double> entry : ranked) {
            ids.add(entry.getKey());
        }
        return ids;
    }

    public static double scoreOf(String id, List<String> vectorIds, List<String> keywordIds, int rrfK) {
        int k = rrfK > 0 ? rrfK : DEFAULT_K;
        double score = 0;
        int v = vectorIds != null ? vectorIds.indexOf(id) : -1;
        int w = keywordIds != null ? keywordIds.indexOf(id) : -1;
        if (v >= 0) {
            score += 1.0 / (k + v + 1);
        }
        if (w >= 0) {
            score += 1.0 / (k + w + 1);
        }
        return score;
    }

    private static void addRanks(Map<String, Double> scores, List<String> ids, int k) {
        if (ids == null) {
            return;
        }
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (id == null || id.isBlank()) {
                continue;
            }
            scores.merge(id, 1.0 / (k + i + 1), Double::sum);
        }
    }
}
