package com.example.agentplatform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 知识库不可变物理索引版本实体（Index Version）
 * 承载底层物理引擎、向量模型、维度、切分策略与发布状态机
 */
@Entity
@Table(
        name = "knowledge_index_versions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_kiv_kb_version", columnNames = {"knowledge_base_id", "version_no"})
        }
)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class KnowledgeIndexVersion {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "knowledge_base_id", nullable = false, length = 64)
    private String knowledgeBaseId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo = 1;

    @Column(name = "engine_type", nullable = false, length = 32)
    private String engineType = "DIFY";

    @Column(name = "provider_handle", length = 128)
    private String providerHandle;

    /**
     * 状态机：DRAFT, BUILDING, VALIDATING, READY, RETIRED, FAILED, REJECTED
     */
    @Column(nullable = false, length = 32)
    private String status = "DRAFT";

    @Column(name = "parse_config", columnDefinition = "TEXT")
    private String parseConfig;

    @Column(name = "chunk_config", columnDefinition = "TEXT")
    private String chunkConfig;

    @Column(name = "embedding_model", length = 64)
    private String embeddingModel = "text-embedding-v3";

    @Column(name = "embedding_provider", length = 64)
    private String embeddingProvider = "langgenius/tongyi/tongyi";

    private Integer dimension = 1024;

    @Column(name = "distance_metric", length = 32)
    private String distanceMetric = "COSINE";

    @Column(name = "rerank_model", length = 64)
    private String rerankModel = "qwen3-rerank";

    @Column(name = "search_method", length = 32)
    private String searchMethod = "hybrid_search";

    @Column(name = "top_k")
    private Integer topK = 3;

    @Column(name = "score_threshold")
    private Double scoreThreshold = 0.5;

    @Column(name = "vector_weight")
    private Double vectorWeight = 0.7;

    @Column(name = "keyword_weight")
    private Double keywordWeight = 0.3;

    @Column(name = "source_snapshot_no")
    private Integer sourceSnapshotNo = 1;

    @Column(name = "config_hash", length = 64)
    private String configHash;

    @Column(name = "total_documents")
    private Integer totalDocuments = 0;

    @Column(name = "total_chunks")
    private Integer totalChunks = 0;

    @Column(name = "total_tokens")
    private Long totalTokens = 0L;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = "kiv-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        if (this.status == null || this.status.isBlank()) {
            this.status = "DRAFT";
        }
        if (this.engineType == null || this.engineType.isBlank()) {
            this.engineType = "DIFY";
        }
        if (this.dimension == null) {
            this.dimension = 1024;
        }
        if (this.distanceMetric == null) {
            this.distanceMetric = "COSINE";
        }
        if (this.totalDocuments == null) {
            this.totalDocuments = 0;
        }
        if (this.totalChunks == null) {
            this.totalChunks = 0;
        }
        if (this.totalTokens == null) {
            this.totalTokens = 0L;
        }
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public KnowledgeIndexVersion() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }

    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }

    public String getEngineType() { return engineType; }
    public void setEngineType(String engineType) { this.engineType = engineType; }

    public String getProviderHandle() { return providerHandle; }
    public void setProviderHandle(String providerHandle) { this.providerHandle = providerHandle; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getParseConfig() { return parseConfig; }
    public void setParseConfig(String parseConfig) { this.parseConfig = parseConfig; }

    public String getChunkConfig() { return chunkConfig; }
    public void setChunkConfig(String chunkConfig) { this.chunkConfig = chunkConfig; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public String getEmbeddingProvider() { return embeddingProvider; }
    public void setEmbeddingProvider(String embeddingProvider) { this.embeddingProvider = embeddingProvider; }

    public Integer getDimension() { return dimension; }
    public void setDimension(Integer dimension) { this.dimension = dimension; }

    public String getDistanceMetric() { return distanceMetric; }
    public void setDistanceMetric(String distanceMetric) { this.distanceMetric = distanceMetric; }

    public String getRerankModel() { return rerankModel; }
    public void setRerankModel(String rerankModel) { this.rerankModel = rerankModel; }

    public String getSearchMethod() { return searchMethod; }
    public void setSearchMethod(String searchMethod) { this.searchMethod = searchMethod; }

    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }

    public Double getScoreThreshold() { return scoreThreshold; }
    public void setScoreThreshold(Double scoreThreshold) { this.scoreThreshold = scoreThreshold; }

    public Double getVectorWeight() { return vectorWeight; }
    public void setVectorWeight(Double vectorWeight) { this.vectorWeight = vectorWeight; }

    public Double getKeywordWeight() { return keywordWeight; }
    public void setKeywordWeight(Double keywordWeight) { this.keywordWeight = keywordWeight; }

    public Integer getSourceSnapshotNo() { return sourceSnapshotNo; }
    public void setSourceSnapshotNo(Integer sourceSnapshotNo) { this.sourceSnapshotNo = sourceSnapshotNo; }

    public String getConfigHash() { return configHash; }
    public void setConfigHash(String configHash) { this.configHash = configHash; }

    public Integer getTotalDocuments() { return totalDocuments; }
    public void setTotalDocuments(Integer totalDocuments) { this.totalDocuments = totalDocuments; }

    public Integer getTotalChunks() { return totalChunks; }
    public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }

    public Long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Long totalTokens) { this.totalTokens = totalTokens; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
