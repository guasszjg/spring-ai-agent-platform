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

@Entity
@Table(name = "knowledge_documents", indexes = {
    @Index(name = "idx_kdoc_kb_id", columnList = "knowledgeBaseId"),
    @Index(name = "idx_kdoc_ext_id", columnList = "externalDocId")
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class KnowledgeDocument {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String knowledgeBaseId;

    @Column(length = 128)
    private String externalDocId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 32)
    private String extension;

    private Long fileSize = 0L;

    private Long wordCount = 0L;

    private Long tokenCount = 0L;

    /**
     * 索引状态：waiting (排队), indexing (处理中), completed (已完成), error (失败)
     */
    @Column(length = 32)
    private String indexingStatus = "waiting";

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Boolean enabled = true;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = "doc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        if (this.fileSize == null) {
            this.fileSize = 0L;
        }
        if (this.wordCount == null) {
            this.wordCount = 0L;
        }
        if (this.tokenCount == null) {
            this.tokenCount = 0L;
        }
        if (this.indexingStatus == null) {
            this.indexingStatus = "waiting";
        }
        if (this.enabled == null) {
            this.enabled = true;
        }
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public KnowledgeDocument() {
    }

    public KnowledgeDocument(String knowledgeBaseId, String externalDocId, String name, String extension, Long fileSize) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.externalDocId = externalDocId;
        this.name = name;
        this.extension = extension;
        this.fileSize = fileSize;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(String knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getExternalDocId() {
        return externalDocId;
    }

    public void setExternalDocId(String externalDocId) {
        this.externalDocId = externalDocId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getExtension() {
        return extension;
    }

    public void setExtension(String extension) {
        this.extension = extension;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Long getWordCount() {
        return wordCount;
    }

    public void setWordCount(Long wordCount) {
        this.wordCount = wordCount;
    }

    public Long getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Long tokenCount) {
        this.tokenCount = tokenCount;
    }

    public String getIndexingStatus() {
        return indexingStatus;
    }

    public void setIndexingStatus(String indexingStatus) {
        this.indexingStatus = indexingStatus;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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
}
