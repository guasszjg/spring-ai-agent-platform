package com.shimeta.rag.dto;

import java.util.Map;

/**
 * 知识库成本归集与可观测性统计 DTO (Knowledge Cost & Governance Stats)
 * 遵循《Spring-AI自研RAG双引擎设计.md》第 10 章成本模型
 */
public record KnowledgeCostStatsDto(
        String knowledgeBaseId,
        String knowledgeBaseName,
        String provider,
        long embeddingTokens,
        long retrievalTokens,
        long rerankCalls,
        long rerankTokens,
        double estimatedCostYuan,
        double embeddingCostYuan,
        double rerankCostYuan,
        long budgetSavedTokens,
        Map<String, Object> formatSupport,
        Map<String, Object> ocrStatus
) {
    /** 嵌入与重排都按约 0.5 元 / 1M Tokens 估算，实际价格以所配模型的厂商为准 */
    private static final double YUAN_PER_TOKEN = 0.0000005;

    public static double estimateCost(long embeddingTokens, long rerankTokens) {
        return round4((embeddingTokens + rerankTokens) * YUAN_PER_TOKEN);
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    public static KnowledgeCostStatsDto of(
            String id,
            String name,
            String provider,
            long embeddingTokens,
            long retrievalTokens,
            long rerankCalls,
            long rerankTokens,
            boolean ocrAvailable,
            String ocrProvider
    ) {
        double embedCost = estimateCost(embeddingTokens, 0);
        double rkCost = estimateCost(0, rerankTokens);
        double totalCost = estimateCost(embeddingTokens, rerankTokens);

        // 估算 Token 预算制节省的 Token 数量 (原先 12块*1500字符 = ~23400 Tokens，现在预算为 3000)
        long savedTokens = Math.max(0, retrievalTokens * 3);

        Map<String, Object> formatSupport = Map.of(
                "supportedFormats", new String[]{"TXT", "MD", "CSV", "TSV", "JSON", "DOCX", "PDF", "PNG", "JPG"},
                "tableHeaderAttachment", true,
                "docxOoxmlParser", true,
                "pdfStreamParser", true
        );

        Map<String, Object> ocr = Map.of(
                "enabled", ocrAvailable,
                "provider", ocrProvider != null ? ocrProvider : "DISABLED",
                "autoScanDetection", true
        );

        return new KnowledgeCostStatsDto(
                id, name, provider,
                embeddingTokens, retrievalTokens, rerankCalls, rerankTokens,
                totalCost, embedCost, rkCost, savedTokens,
                formatSupport, ocr
        );
    }
}
