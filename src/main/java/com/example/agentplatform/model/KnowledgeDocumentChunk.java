package com.example.agentplatform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 平台自研 RAG 引擎的物理分段切片实体 (Chunk)
 * 支持存储分段文本、Token预估量、多维向量 Embedding 与元数据
 */
@Entity
@Table(name = "knowledge_document_chunks", indexes = {
    @Index(name = "idx_kdc_kb_id", columnList = "knowledgeBaseId"),
    @Index(name = "idx_kdc_doc_id", columnList = "documentId"),
    @Index(name = "idx_kdc_faq_id", columnList = "faqId"),
    @Index(name = "idx_kdc_kb_chunk", columnList = "knowledgeBaseId, chunkIndex")
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class KnowledgeDocumentChunk {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String knowledgeBaseId;

    @Column(length = 64)
    private String documentId;

    @Column(length = 64)
    private String faqId;

    @Column(nullable = false)
    private Integer chunkIndex = 0;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    private Integer characterCount = 0;

    private Long tokenCount = 0L;

    /**
     * 父块标识（用于父子切分展开，可为空）
     */
    @Column(length = 64)
    private String parentChunkId;

    /**
     * 父块完整内容（展开时使用）
     */
    @Column(columnDefinition = "TEXT")
    private String parentContent;

    /**
     * 切片类型：STANDALONE (独立切片), PARENT (父块), CHILD (子块)
     */
    @Column(length = 32)
    private String chunkType = "CHILD";

    /**
     * 向量值 (JSON Array 字符串或紧凑浮点格式)，支持 1024 维密集向量
     */
    @Column(columnDefinition = "TEXT")
    private String embedding;

    /**
     * 扩展元数据 (JSON)，包含 sourceName, pageNumber 等
     */
    @Column(columnDefinition = "TEXT")
    private String metadataJson;

    @Column(nullable = false)
    private Boolean enabled = true;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (enabled == null) {
            enabled = true;
        }
        if (chunkIndex == null) {
            chunkIndex = 0;
        }
        if (content != null) {
            characterCount = content.length();
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
        if (content != null) {
            characterCount = content.length();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public String getFaqId() { return faqId; }
    public void setFaqId(String faqId) { this.faqId = faqId; }

    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getCharacterCount() { return characterCount; }
    public void setCharacterCount(Integer characterCount) { this.characterCount = characterCount; }

    public Long getTokenCount() { return tokenCount; }
    public void setTokenCount(Long tokenCount) { this.tokenCount = tokenCount; }

    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }

    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getParentChunkId() { return parentChunkId; }
    public void setParentChunkId(String parentChunkId) { this.parentChunkId = parentChunkId; }

    public String getParentContent() { return parentContent; }
    public void setParentContent(String parentContent) { this.parentContent = parentContent; }

    public String getChunkType() { return chunkType; }
    public void setChunkType(String chunkType) { this.chunkType = chunkType; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
