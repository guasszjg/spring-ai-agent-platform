package com.example.agentplatform.rag.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DifyDatasetDto {

    private String id;
    private String name;
    private String description;

    @JsonProperty("document_count")
    private Integer documentCount = 0;

    @JsonProperty("word_count")
    private Long wordCount = 0L;

    @JsonProperty("indexing_technique")
    private String indexingTechnique;

    private String provider;
    private String permission;

    @JsonProperty("embedding_model")
    private String embeddingModel;

    @JsonProperty("embedding_model_provider")
    private String embeddingModelProvider;

    @JsonProperty("retrieval_model_dict")
    private java.util.Map<String, Object> retrievalModelDict;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

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

    public Integer getDocumentCount() {
        return documentCount;
    }

    public void setDocumentCount(Integer documentCount) {
        this.documentCount = documentCount;
    }

    public Long getWordCount() {
        return wordCount;
    }

    public void setWordCount(Long wordCount) {
        this.wordCount = wordCount;
    }

    public String getIndexingTechnique() {
        return indexingTechnique;
    }

    public void setIndexingTechnique(String indexingTechnique) {
        this.indexingTechnique = indexingTechnique;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
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

    public String getEmbeddingModelProvider() {
        return embeddingModelProvider;
    }

    public void setEmbeddingModelProvider(String embeddingModelProvider) {
        this.embeddingModelProvider = embeddingModelProvider;
    }

    public java.util.Map<String, Object> getRetrievalModelDict() {
        return retrievalModelDict;
    }

    public void setRetrievalModelDict(java.util.Map<String, Object> retrievalModelDict) {
        this.retrievalModelDict = retrievalModelDict;
    }
}
