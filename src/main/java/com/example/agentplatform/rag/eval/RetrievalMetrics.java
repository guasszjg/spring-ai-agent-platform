package com.example.agentplatform.rag.eval;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    public static double hitAtK(Set<String> expectedIds, List<String> retrievedIds, int k) {
        if (expectedIds == null || expectedIds.isEmpty()) {
            return 0;
        }
        Set<String> expected = normalize(expectedIds);
        int limit = Math.min(k, retrievedIds == null ? 0 : retrievedIds.size());
        for (int i = 0; i < limit; i++) {
            if (expected.contains(retrievedIds.get(i))) {
                return 1.0;
            }
        }
        return 0.0;
    }

    public static double recallAtK(Set<String> expectedIds, List<String> retrievedIds, int k) {
        if (expectedIds == null || expectedIds.isEmpty()) {
            return 0;
        }
        Set<String> expected = normalize(expectedIds);
        int limit = Math.min(k, retrievedIds == null ? 0 : retrievedIds.size());
        int hit = 0;
        for (int i = 0; i < limit; i++) {
            if (expected.contains(retrievedIds.get(i))) {
                hit++;
            }
        }
        return hit / (double) expected.size();
    }

    public static double ndcgAtK(Set<String> expectedIds, List<String> retrievedIds, int k) {
        if (expectedIds == null || expectedIds.isEmpty() || retrievedIds == null || retrievedIds.isEmpty()) {
            return 0;
        }
        Set<String> expected = normalize(expectedIds);
        int limit = Math.min(k, retrievedIds.size());
        double dcg = 0;
        for (int i = 0; i < limit; i++) {
            if (expected.contains(retrievedIds.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        int idealHits = Math.min(expected.size(), k);
        double idcg = 0;
        for (int i = 0; i < idealHits; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    private static Set<String> normalize(Set<String> ids) {
        Set<String> out = new HashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim());
            }
        }
        return out;
    }
}
