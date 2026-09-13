package com.example.agentplatform.rag.engine;

import java.util.Collections;
import java.util.Map;

/**
 * 引擎无关的统一检索请求参数
 * 支持基础检索模式、权重与重排参数，以及 P2/P3/P4 扩展：
 * - P2: 查询改写、父子分块展开与 Token 预算
 * - P4: 语义缓存开关、图文跨模态 (queryType, queryImageUrl, injectImagesToLlm)、GraphRAG 实体扩展
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
        Boolean rewriteEnabled,      // P2: 是否启用 Query 意图理解与改写
        Boolean expandParent,        // P2: 是否展开父块大段落
        Integer maxContextTokens,    // P2: Token 预算上限 (默认 3000)
        Map<String, Object> extraParams,
        Boolean cacheEnabled,        // P4: 是否使用语义缓存 (默认 true)
        String queryType,            // P4: 查询类型 TEXT / IMAGE / MULTIMODAL (默认 TEXT)
        String queryImageUrl,        // P4: 以图搜图时的图片 URL
        Boolean injectImagesToLlm,   // P4: 原图是否注入 LLM 上下文 (默认 false, 遵循第18章)
        Boolean graphSearchEnabled   // P4: 是否开启 GraphRAG 实体多跳检索 (默认 false)
) {
    /**
     * 兼容旧 15 参数构造器 (P1~P3)
     */
    public RetrievalRequest(String knowledgeBaseId, String query, Integer topK, Double scoreThreshold,
                            String searchMethod, Boolean rerankEnabled, String rerankModel,
                            Double vectorWeight, Double keywordWeight, EngineType engineOverride,
                            String indexVersionId, Boolean rewriteEnabled, Boolean expandParent,
                            Integer maxContextTokens, Map<String, Object> extraParams) {
        this(knowledgeBaseId, query, topK, scoreThreshold, searchMethod, rerankEnabled, rerankModel,
                vectorWeight, keywordWeight, engineOverride, indexVersionId, rewriteEnabled, expandParent,
                maxContextTokens, extraParams, true, "TEXT", null, false, false);
    }

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
        private Boolean rewriteEnabled = false;
        private Boolean expandParent = true;
        private Integer maxContextTokens = 3000;
        private Map<String, Object> extraParams;
        private Boolean cacheEnabled = true;
        private String queryType = "TEXT";
        private String queryImageUrl;
        private Boolean injectImagesToLlm = false;
        private Boolean graphSearchEnabled = false;

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
        public Builder rewriteEnabled(Boolean rewriteEnabled) { this.rewriteEnabled = rewriteEnabled; return this; }
        public Builder expandParent(Boolean expandParent) { this.expandParent = expandParent; return this; }
        public Builder maxContextTokens(Integer maxContextTokens) { this.maxContextTokens = maxContextTokens; return this; }
        public Builder extraParams(Map<String, Object> extraParams) { this.extraParams = extraParams; return this; }
        public Builder cacheEnabled(Boolean cacheEnabled) { this.cacheEnabled = cacheEnabled; return this; }
        public Builder queryType(String queryType) { this.queryType = queryType; return this; }
        public Builder queryImageUrl(String queryImageUrl) { this.queryImageUrl = queryImageUrl; return this; }
        public Builder injectImagesToLlm(Boolean injectImagesToLlm) { this.injectImagesToLlm = injectImagesToLlm; return this; }
        public Builder graphSearchEnabled(Boolean graphSearchEnabled) { this.graphSearchEnabled = graphSearchEnabled; return this; }

        public RetrievalRequest build() {
            return new RetrievalRequest(
                    knowledgeBaseId, query, topK, scoreThreshold, searchMethod,
                    rerankEnabled, rerankModel, vectorWeight, keywordWeight,
                    engineOverride, indexVersionId,
                    rewriteEnabled != null ? rewriteEnabled : false,
                    expandParent != null ? expandParent : true,
                    maxContextTokens != null ? maxContextTokens : 3000,
                    extraParams != null ? extraParams : Collections.emptyMap(),
                    cacheEnabled != null ? cacheEnabled : true,
                    queryType != null ? queryType : "TEXT",
                    queryImageUrl,
                    injectImagesToLlm != null ? injectImagesToLlm : false,
                    graphSearchEnabled != null ? graphSearchEnabled : false
            );
        }
    }
}
