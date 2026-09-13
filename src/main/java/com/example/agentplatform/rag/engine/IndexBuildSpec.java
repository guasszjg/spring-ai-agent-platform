package com.example.agentplatform.rag.engine;

import java.util.Collections;
import java.util.Map;

/**
 * 物理索引构建/绑定规约参数
 */
public record IndexBuildSpec(
        String knowledgeBaseId,
        String knowledgeBaseName,
        String description,
        Integer versionNo,
        EngineType engineType,
        String embeddingModel,
        String embeddingProvider,
        Integer dimensions,
        String searchMethod,
        Integer topK,
        Double scoreThreshold,
        Boolean rerankEnabled,
        String rerankMode,
        String rerankModel,
        String rerankModelProvider,
        Double vectorWeight,
        Double keywordWeight,
        Map<String, Object> extraConfig
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String knowledgeBaseId;
        private String knowledgeBaseName;
        private String description;
        private Integer versionNo = 1;
        private EngineType engineType = EngineType.DIFY;
        private String embeddingModel = "text-embedding-v3";
        private String embeddingProvider = "langgenius/tongyi/tongyi";
        private Integer dimensions = 1024;
        private String searchMethod = "hybrid_search";
        private Integer topK = 3;
        private Double scoreThreshold = 0.5;
        private Boolean rerankEnabled = true;
        private String rerankMode = "weighted_score";
        private String rerankModel = "qwen3-rerank";
        private String rerankModelProvider = "langgenius/tongyi/tongyi";
        private Double vectorWeight = 0.7;
        private Double keywordWeight = 0.3;
        private Map<String, Object> extraConfig;

        public Builder knowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; return this; }
        public Builder knowledgeBaseName(String knowledgeBaseName) { this.knowledgeBaseName = knowledgeBaseName; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder versionNo(Integer versionNo) { this.versionNo = versionNo; return this; }
        public Builder engineType(EngineType engineType) { this.engineType = engineType; return this; }
        public Builder embeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; return this; }
        public Builder embeddingProvider(String embeddingProvider) { this.embeddingProvider = embeddingProvider; return this; }
        public Builder dimensions(Integer dimensions) { this.dimensions = dimensions; return this; }
        public Builder searchMethod(String searchMethod) { this.searchMethod = searchMethod; return this; }
        public Builder topK(Integer topK) { this.topK = topK; return this; }
        public Builder scoreThreshold(Double scoreThreshold) { this.scoreThreshold = scoreThreshold; return this; }
        public Builder rerankEnabled(Boolean rerankEnabled) { this.rerankEnabled = rerankEnabled; return this; }
        public Builder rerankMode(String rerankMode) { this.rerankMode = rerankMode; return this; }
        public Builder rerankModel(String rerankModel) { this.rerankModel = rerankModel; return this; }
        public Builder rerankModelProvider(String rerankModelProvider) { this.rerankModelProvider = rerankModelProvider; return this; }
        public Builder vectorWeight(Double vectorWeight) { this.vectorWeight = vectorWeight; return this; }
        public Builder keywordWeight(Double keywordWeight) { this.keywordWeight = keywordWeight; return this; }
        public Builder extraConfig(Map<String, Object> extraConfig) { this.extraConfig = extraConfig; return this; }

        public IndexBuildSpec build() {
            return new IndexBuildSpec(
                    knowledgeBaseId, knowledgeBaseName, description, versionNo, engineType,
                    embeddingModel, embeddingProvider, dimensions, searchMethod,
                    topK, scoreThreshold, rerankEnabled, rerankMode,
                    rerankModel, rerankModelProvider, vectorWeight, keywordWeight,
                    extraConfig != null ? extraConfig : Collections.emptyMap()
            );
        }
    }
}
