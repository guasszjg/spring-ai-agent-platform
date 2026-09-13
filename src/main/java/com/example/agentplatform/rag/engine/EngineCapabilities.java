package com.example.agentplatform.rag.engine;

import java.util.List;

/**
 * 引擎能力特性清单（供前端/向导与校验层查询）
 */
public record EngineCapabilities(
        EngineType engineType,
        String displayName,
        List<String> supportedExtensions,
        List<String> supportedSearchMethods,
        boolean supportsRerank,
        boolean supportsMultimodal,
        boolean supportsCustomDimensions,
        List<String> recommendedEmbeddingModels
) {
    public static EngineCapabilities difyDefaults() {
        return new EngineCapabilities(
                EngineType.DIFY,
                "Dify 外挂引擎",
                List.of("TXT", "MARKDOWN", "MD", "PDF", "HTML", "HTM", "XLSX", "XLS", "DOCX", "CSV", "VTT", "PROPERTIES"),
                List.of("hybrid_search", "semantic_search", "full_text_search"),
                true,
                false,
                false,
                List.of("text-embedding-v3")
        );
    }

    public static EngineCapabilities springAiDefaults() {
        return new EngineCapabilities(
                EngineType.SPRING_AI,
                "Spring AI 自研引擎",
                List.of("TXT", "MARKDOWN", "MD", "PDF", "DOCX", "CSV", "JSON", "HTML"),
                List.of("hybrid_search", "semantic_search", "full_text_search"),
                true,
                true,
                true,
                List.of("text-embedding-v3", "text-embedding-ada-002")
        );
    }
}
