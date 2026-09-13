package com.example.agentplatform.rag.engine;

import com.example.agentplatform.rag.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowEvaluationResultTest {

    @Test
    @DisplayName("测试影子评测 Jaccard 重叠率计算与指标")
    void testShadowOverlapCalculation() {
        RetrievedChunk chunk1 = RetrievedChunk.builder()
                .chunkId("c1")
                .content("Spring AI 是新一代 Java AI 应用框架，支持原生 RAG。")
                .tokenCount(20)
                .build();
        RetrievedChunk chunk2 = RetrievedChunk.builder()
                .chunkId("c2")
                .content("Dify 是一款优秀的开源 LLMOps 协同应用平台。")
                .tokenCount(25)
                .build();
        RetrievedChunk chunk3 = RetrievedChunk.builder()
                .chunkId("c3")
                .content("PostgreSQL pgvector 提供企业级高效向量相似度检索。")
                .tokenCount(30)
                .build();

        List<RetrievedChunk> primary = List.of(chunk1, chunk3);
        List<RetrievedChunk> secondary = List.of(chunk1, chunk2);

        ShadowEvaluationResult result = ShadowEvaluationResult.of(
                "Spring AI 原生 RAG 架构设计",
                "SPRING_AI",
                "DIFY",
                primary,
                secondary,
                45L,
                110L,
                Map.of("testKey", "testVal")
        );

        assertThat(result.primaryEngine()).isEqualTo("SPRING_AI");
        assertThat(result.secondaryEngine()).isEqualTo("DIFY");
        assertThat(result.primaryLatencyMs()).isEqualTo(45L);
        assertThat(result.secondaryLatencyMs()).isEqualTo(110L);
        assertThat(result.latencyDiffMs()).isEqualTo(-65L); // 自研快 65ms
        assertThat(result.primaryTokens()).isEqualTo(50);
        assertThat(result.secondaryTokens()).isEqualTo(45);
        assertThat(result.overlapRatio()).isGreaterThan(0.0);
        assertThat(result.primaryOnlyCount()).isEqualTo(1); // c3
        assertThat(result.secondaryOnlyCount()).isEqualTo(1); // c2
    }
}
