package com.shimeta.rag.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionTest {

    @Test
    void weightsDecideWhichRouteWins() {
        List<String> vector = List.of("v", "k");
        List<String> keyword = List.of("k", "v");
        assertThat(RrfFusion.fuse(vector, keyword, 60, 1.8, 0.2)).containsExactly("v", "k");
        assertThat(RrfFusion.fuse(vector, keyword, 60, 0.2, 1.8)).containsExactly("k", "v");
        assertThat(RrfFusion.scoreOf("v", vector, keyword, 60, 1.0, 1.0))
                .isEqualTo(RrfFusion.scoreOf("v", vector, keyword, 60));
    }

    @Test
    void prefersDocumentsThatRankOnBothLists() {
        List<String> vector = List.of("a", "b", "c");
        List<String> keyword = List.of("c", "a", "d");
        List<String> fused = RrfFusion.fuse(vector, keyword, 60);
        assertThat(fused.get(0)).isEqualTo("a");
        assertThat(fused).containsExactly("a", "c", "b", "d");
    }
}
