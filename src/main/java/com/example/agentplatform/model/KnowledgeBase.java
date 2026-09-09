package com.example.agentplatform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "knowledge_bases")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class KnowledgeBase {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 64)
    private String avatar = "📚";

    /**
     * RAG 提供方：DIFY (当前外挂), SPRING_AI_NATIVE (未来原生拓展)
     */
    @Column(length = 32, nullable = false)
    private String provider = "DIFY";

    /**
     * 外部 Dify Dataset ID (1对1映射)
     */
    @Column(length = 128)
    private String externalDatasetId;

    @Column(length = 64)
    private String indexingTechnique = "high_quality";

    @Column(length = 32)
    private String permission = "only_me";

    private Integer documentCount = 0;

    private Long wordCount = 0L;

    private Integer faqCount = 0;

    private Boolean enabled = true;

    @Column(length = 64)
    private String embeddingModel = "text-embedding-v3";

    @Column(length = 64)
    private String embeddingProvider = "langgenius/tongyi/tongyi";

    @Column(length = 32)
    private String searchMethod = "hybrid_search";

    private Integer topK = 3;

    private Boolean rerankEnabled = true;

    @Column(length = 32)
    private String rerankMode = "weighted_score";

    @Column(length = 64)
    private String rerankModel = "qwen3-rerank";

    @Column(length = 64)
    private String rerankModelProvider = "langgenius/tongyi/tongyi";

    private Double vectorWeight = 0.7;

    private Double keywordWeight = 0.3;

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    @Column(name = "owner_username", length = 64)
    private String ownerUsername;

    @Column(name = "is_system")
    private Boolean isSystem = false;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = "kb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        if (this.avatar == null || this.avatar.isBlank()) {
            this.avatar = "📚";
        }
        if (this.provider == null || this.provider.isBlank()) {
            this.provider = "DIFY";
        }
        if (this.indexingTechnique == null) {
            this.indexingTechnique = "high_quality";
        }
        if (this.permission == null) {
            this.permission = "only_me";
        }
        if (this.documentCount == null) {
            this.documentCount = 0;
        }
        if (this.wordCount == null) {
            this.wordCount = 0L;
        }
        if (this.faqCount == null) {
            this.faqCount = 0;
        }
        if (this.enabled == null) {
            this.enabled = true;
        }
        if (this.embeddingModel == null || this.embeddingModel.isBlank()) {
            this.embeddingModel = "text-embedding-v3";
        }
        if (this.embeddingProvider == null || this.embeddingProvider.isBlank()) {
            this.embeddingProvider = "langgenius/tongyi/tongyi";
        }
        if (this.searchMethod == null || this.searchMethod.isBlank()) {
            this.searchMethod = "hybrid_search";
        }
        if (this.topK == null || this.topK <= 0) {
            this.topK = 3;
        }
        if (this.rerankEnabled == null) {
            this.rerankEnabled = true;
        }
        if (this.rerankMode == null || this.rerankMode.isBlank()) {
            this.rerankMode = "weighted_score";
        }
        if (this.rerankModel == null || this.rerankModel.isBlank()) {
            this.rerankModel = "qwen3-rerank";
        }
        if (this.rerankModelProvider == null || this.rerankModelProvider.isBlank()) {
            this.rerankModelProvider = "langgenius/tongyi/tongyi";
        }
        if (this.vectorWeight == null || this.vectorWeight <= 0) {
            this.vectorWeight = 0.7;
        }
        if (this.keywordWeight == null || this.keywordWeight <= 0) {
            this.keywordWeight = 0.3;
        }
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public KnowledgeBase() {
    }

    public KnowledgeBase(String name, String description, String avatar, String provider, String externalDatasetId) {
        this.name = name;
        this.description = description;
        this.avatar = avatar;
        this.provider = provider;
        this.externalDatasetId = externalDatasetId;
    }

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

    public String getExternalDatasetId() {
        return externalDatasetId;
    }

    public void setExternalDatasetId(String externalDatasetId) {
        this.externalDatasetId = externalDatasetId;
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

    public Integer getFaqCount() {
        return faqCount;
    }

    public void setFaqCount(Integer faqCount) {
        this.faqCount = faqCount;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
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

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerUsername() {
        return ownerUsername;
    }

    public void setOwnerUsername(String ownerUsername) {
        this.ownerUsername = ownerUsername;
    }

    public Boolean getIsSystem() {
        return isSystem;
    }

    public void setIsSystem(Boolean isSystem) {
        this.isSystem = isSystem;
    }
}
