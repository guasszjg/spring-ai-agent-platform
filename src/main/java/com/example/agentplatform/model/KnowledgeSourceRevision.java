package com.example.agentplatform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 知识库源文件修订版本快照（Source Revision）
 * 确保存储对象不可原地覆盖，构建与重试始终基于确定性文件快照
 */
@Entity
@Table(
        name = "knowledge_source_revisions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ksr_doc_revision", columnNames = {"document_id", "revision_no"})
        }
)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class KnowledgeSourceRevision {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "knowledge_base_id", nullable = false, length = 64)
    private String knowledgeBaseId;

    @Column(name = "document_id", nullable = false, length = 64)
    private String documentId;

    @Column(name = "revision_no", nullable = false)
    private Integer revisionNo = 1;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(length = 32)
    private String extension;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes = 0L;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "storage_type", nullable = false, length = 32)
    private String storageType = "LOCAL";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = "ksr-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        if (this.storageType == null || this.storageType.isBlank()) {
            this.storageType = "LOCAL";
        }
        if (this.sizeBytes == null) {
            this.sizeBytes = 0L;
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public KnowledgeSourceRevision() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public Integer getRevisionNo() { return revisionNo; }
    public void setRevisionNo(Integer revisionNo) { this.revisionNo = revisionNo; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getExtension() { return extension; }
    public void setExtension(String extension) { this.extension = extension; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public String getStorageType() { return storageType; }
    public void setStorageType(String storageType) { this.storageType = storageType; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
