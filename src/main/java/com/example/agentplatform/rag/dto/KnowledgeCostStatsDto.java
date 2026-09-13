package com.example.agentplatform.rag.dto;

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
        double estimatedCostYuan,
        double embeddingCostYuan,
        double rerankCostYuan,
        long budgetSavedTokens,
        Map<String, Object> formatSupport,
        Map<String, Object> ocrStatus
) {
    public static KnowledgeCostStatsDto of(
            String id,
            String name,
            String provider,
            long embeddingTokens,
            long retrievalTokens,
            long rerankCalls,
            boolean ocrAvailable,
            String ocrProvider
    ) {
        // 嵌入成本: 约 0.5 元 / 1M Tokens (0.0000005 元/token)
        double embedCost = Math.round((embeddingTokens * 0.0000005) * 10000.0) / 10000.0;
        // 重排成本: 约 0.003 元 / 次调用
        double rkCost = Math.round((rerankCalls * 0.003) * 10000.0) / 10000.0;
        double totalCost = Math.round((embedCost + rkCost) * 10000.0) / 10000.0;

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
                embeddingTokens, retrievalTokens, rerankCalls,
                totalCost, embedCost, rkCost, savedTokens,
                formatSupport, ocr
        );
    }
}
