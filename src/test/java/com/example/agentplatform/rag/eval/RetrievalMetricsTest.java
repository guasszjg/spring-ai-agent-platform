package com.example.agentplatform.rag.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalMetricsTest {

    @Test
    void hitRecallNdcgOnSimpleRanking() {
        Set<String> expected = Set.of("c1", "c2");
        List<String> retrieved = List.of("c9", "c1", "c2", "c8");
        assertThat(RetrievalMetrics.hitAtK(expected, retrieved, 5)).isEqualTo(1.0);
        assertThat(RetrievalMetrics.hitAtK(expected, retrieved, 1)).isEqualTo(0.0);
        assertThat(RetrievalMetrics.recallAtK(expected, retrieved, 10)).isEqualTo(1.0);
        assertThat(RetrievalMetrics.ndcgAtK(expected, retrieved, 10)).isGreaterThan(0.5);
    }
}
