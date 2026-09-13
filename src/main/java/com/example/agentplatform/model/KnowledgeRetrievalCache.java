package com.example.agentplatform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * RAG 语义缓存实体 (Semantic Cache)
 * 遵循《Spring-AI自研RAG双引擎设计.md》第 10 章与第 18 章
 * 对相似 Query (余弦相似度 >= 0.95) 直接复用检索结果，节约 100% 检索与重排成本，响应耗时 < 5ms
 */
@Entity
@Table(name = "knowledge_retrieval_cache", indexes = {
    @Index(name = "idx_krc_kb_id", columnList = "knowledgeBaseId"),
    @Index(name = "idx_krc_expires", columnList = "expiresAt")
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class KnowledgeRetrievalCache {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String knowledgeBaseId;

    @Column(length = 64)
    private String indexVersionId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String queryText;

    /**
     * 查询向量 (JSON 格式)，用于快速余弦相似度比对
     */
    @Column(columnDefinition = "TEXT")
    private String queryVector;

    /**
     * 序列化的检索切片结果 (JSON 数组)
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String resultJson;

    /**
     * 命中次数
     */
    private Long hitCount = 1L;

    /**
     * 累计节约的检索耗时 (ms)
     */
    private Long latencySavedMs = 0L;

    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    @PrePersist
    public void prePersist() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (hitCount == null) {
            hitCount = 1L;
        }
        if (latencySavedMs == null) {
            latencySavedMs = 0L;
        }
        createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }

    public String getIndexVersionId() { return indexVersionId; }
    public void setIndexVersionId(String indexVersionId) { this.indexVersionId = indexVersionId; }

    public String getQueryText() { return queryText; }
    public void setQueryText(String queryText) { this.queryText = queryText; }

    public String getQueryVector() { return queryVector; }
    public void setQueryVector(String queryVector) { this.queryVector = queryVector; }

    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }

    public Long getHitCount() { return hitCount; }
    public void setHitCount(Long hitCount) { this.hitCount = hitCount; }

    public Long getLatencySavedMs() { return latencySavedMs; }
    public void setLatencySavedMs(Long latencySavedMs) { this.latencySavedMs = latencySavedMs; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
