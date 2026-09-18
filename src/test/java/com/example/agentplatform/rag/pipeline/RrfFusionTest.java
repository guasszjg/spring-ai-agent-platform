package com.example.agentplatform.rag.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionTest {

    @Test
    void prefersDocumentsThatRankOnBothLists() {
        List<String> vector = List.of("a", "b", "c");
        List<String> keyword = List.of("c", "a", "d");
        List<String> fused = RrfFusion.fuse(vector, keyword, 60);
        assertThat(fused.get(0)).isEqualTo("a");
        assertThat(fused).containsExactly("a", "c", "b", "d");
    }
}
