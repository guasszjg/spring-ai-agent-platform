package com.example.agentplatform.rag.dto;

public class CreateKnowledgeBaseRequest {
    private String name;
    private String description;
    private String avatar = "📚";
    private String provider = "DIFY";
    private String indexingTechnique = "high_quality";
    private String permission = "only_me";
    private String embeddingModel = "text-embedding-v3";
    private String embeddingProvider = "langgenius/tongyi/tongyi";
    private String searchMethod = "hybrid_search";
    private Integer topK = 3;
    private Boolean rerankEnabled = true;
    private String rerankMode = "weighted_score";
    private String rerankModel = "qwen3-rerank";
    private String rerankModelProvider = "langgenius/tongyi/tongyi";
    private Double vectorWeight = 0.7;
    private Double keywordWeight = 0.3;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getIndexingTechnique() {
        return indexingTechnique;
    }

    public void setIndexingTechnique(String indexingTechnique) {
        this.indexingTechnique = indexingTechnique;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public void setEmbeddingProvider(String embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public String getSearchMethod() {
        return searchMethod;
    }

    public void setSearchMethod(String searchMethod) {
        this.searchMethod = searchMethod;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Boolean getRerankEnabled() {
        return rerankEnabled;
    }

    public void setRerankEnabled(Boolean rerankEnabled) {
        this.rerankEnabled = rerankEnabled;
    }

    public String getRerankMode() {
        return rerankMode;
    }

    public void setRerankMode(String rerankMode) {
        this.rerankMode = rerankMode;
    }

    public String getRerankModel() {
        return rerankModel;
    }

    public void setRerankModel(String rerankModel) {
        this.rerankModel = rerankModel;
    }

    public String getRerankModelProvider() {
        return rerankModelProvider;
    }

    public void setRerankModelProvider(String rerankModelProvider) {
        this.rerankModelProvider = rerankModelProvider;
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
}
