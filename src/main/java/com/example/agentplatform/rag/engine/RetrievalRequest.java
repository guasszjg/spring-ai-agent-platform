package com.example.agentplatform.rag.engine;

import java.util.Collections;
import java.util.Map;

/**
 * 引擎无关的统一检索请求参数
 */
public record RetrievalRequest(
        String knowledgeBaseId,
        String query,
        Integer topK,
        Double scoreThreshold,
        String searchMethod,
        Boolean rerankEnabled,
        String rerankModel,
        Double vectorWeight,
        Double keywordWeight,
        EngineType engineOverride,   // L3 调试临时覆盖（可为空）
        String indexVersionId,       // 指定物理索引版本（可为空，默认主版本）
        Map<String, Object> extraParams
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String knowledgeBaseId;
        private String query;
        private Integer topK = 3;
        private Double scoreThreshold;
        private String searchMethod = "hybrid_search";
        private Boolean rerankEnabled = false;
        private String rerankModel;
        private Double vectorWeight = 0.7;
        private Double keywordWeight = 0.3;
        private EngineType engineOverride;
        private String indexVersionId;
        private Map<String, Object> extraParams;

        public Builder knowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; return this; }
        public Builder query(String query) { this.query = query; return this; }
        public Builder topK(Integer topK) { this.topK = topK; return this; }
        public Builder scoreThreshold(Double scoreThreshold) { this.scoreThreshold = scoreThreshold; return this; }
        public Builder searchMethod(String searchMethod) { this.searchMethod = searchMethod; return this; }
        public Builder rerankEnabled(Boolean rerankEnabled) { this.rerankEnabled = rerankEnabled; return this; }
        public Builder rerankModel(String rerankModel) { this.rerankModel = rerankModel; return this; }
        public Builder vectorWeight(Double vectorWeight) { this.vectorWeight = vectorWeight; return this; }
        public Builder keywordWeight(Double keywordWeight) { this.keywordWeight = keywordWeight; return this; }
        public Builder engineOverride(EngineType engineOverride) { this.engineOverride = engineOverride; return this; }
        public Builder indexVersionId(String indexVersionId) { this.indexVersionId = indexVersionId; return this; }
        public Builder extraParams(Map<String, Object> extraParams) { this.extraParams = extraParams; return this; }

        public RetrievalRequest build() {
            return new RetrievalRequest(
                    knowledgeBaseId, query, topK, scoreThreshold, searchMethod,
                    rerankEnabled, rerankModel, vectorWeight, keywordWeight,
                    engineOverride, indexVersionId,
                    extraParams != null ? extraParams : Collections.emptyMap()
            );
        }
    }
}
