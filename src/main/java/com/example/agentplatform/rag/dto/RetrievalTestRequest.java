package com.example.agentplatform.rag.dto;

import java.util.Map;

/**
 * 召回测试请求参数 DTO
 * 允许在不修改线上知识库持久配置的前提下，临时微调检索参数进行调试与对比
 */
public class RetrievalTestRequest {

    private String query;
    private Integer topK;
    private Double scoreThreshold;
    private String searchMethod;
    private Boolean rerankEnabled;
    private String rerankModel;
    private Double vectorWeight;
    private Double keywordWeight;
    private String engineOverride;   // DIFY, SPRING_AI (可为空)
    private String indexVersionId;   // 指定索引版本（可为空）
    private Map<String, Object> extraParams;

    public RetrievalTestRequest() {
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Double getScoreThreshold() {
        return scoreThreshold;
    }

    public void setScoreThreshold(Double scoreThreshold) {
        this.scoreThreshold = scoreThreshold;
    }

    public String getSearchMethod() {
        return searchMethod;
    }

    public void setSearchMethod(String searchMethod) {
        this.searchMethod = searchMethod;
    }

    public Boolean getRerankEnabled() {
        return rerankEnabled;
    }

    public void setRerankEnabled(Boolean rerankEnabled) {
        this.rerankEnabled = rerankEnabled;
    }

    public String getRerankModel() {
        return rerankModel;
    }

    public void setRerankModel(String rerankModel) {
        this.rerankModel = rerankModel;
    }

    public Double getVectorWeight() {
        return vectorWeight;
    }

    public void setVectorWeight(Double vectorWeight) {
        this.vectorWeight = vectorWeight;
    }

    public Double getKeywordWeight() {
        return keywordWeight;
    }

    public void setKeywordWeight(Double keywordWeight) {
        this.keywordWeight = keywordWeight;
    }

    public String getEngineOverride() {
        return engineOverride;
    }

    public void setEngineOverride(String engineOverride) {
        this.engineOverride = engineOverride;
    }

    public String getIndexVersionId() {
        return indexVersionId;
    }

    public void setIndexVersionId(String indexVersionId) {
        this.indexVersionId = indexVersionId;
    }

    public Map<String, Object> getExtraParams() {
        return extraParams;
    }

    public void setExtraParams(Map<String, Object> extraParams) {
        this.extraParams = extraParams;
    }
}
