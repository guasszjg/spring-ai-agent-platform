package com.example.agentplatform.rag.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeCostStatsDtoTest {

    @Test
    @DisplayName("测试知识库成本计算与格式能力汇总")
    void testCostCalculation() {
        KnowledgeCostStatsDto stats = KnowledgeCostStatsDto.of(
                "kb-1001",
                "财务核心知识库",
                "SPRING_AI",
                2_000_000L, // 2M embedding tokens
                50_000L,    // 50k retrieval tokens
                100L,       // 100 rerank calls
                true,
                "LOCAL_PADDLE_OCR"
        );

        assertThat(stats.knowledgeBaseId()).isEqualTo("kb-1001");
        assertThat(stats.knowledgeBaseName()).isEqualTo("财务核心知识库");
        assertThat(stats.provider()).isEqualTo("SPRING_AI");
        assertThat(stats.embeddingTokens()).isEqualTo(2_000_000L);
        assertThat(stats.retrievalTokens()).isEqualTo(50_000L);
        assertThat(stats.rerankCalls()).isEqualTo(100L);

        // 2M tokens * 0.5元/M = 1.0 元
        assertThat(stats.embeddingCostYuan()).isEqualTo(1.0);
        // 100 calls * 0.003元 = 0.3 元
        assertThat(stats.rerankCostYuan()).isEqualTo(0.3);
        // 合计 1.3 元
        assertThat(stats.estimatedCostYuan()).isEqualTo(1.3);

        assertThat(stats.formatSupport()).containsKey("docxOoxmlParser");
        assertThat(stats.ocrStatus().get("enabled")).isEqualTo(true);
        assertThat(stats.ocrStatus().get("provider")).isEqualTo("LOCAL_PADDLE_OCR");
    }
}
