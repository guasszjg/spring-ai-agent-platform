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

/**
 * 知识库异步构建任务实体（Index Job）
 * 具备租约续约、检查点进度、可重入重试与断点续做能力
 */
@Entity
@Table(name = "knowledge_index_jobs")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class KnowledgeIndexJob {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "index_version_id", nullable = false, length = 64)
    private String indexVersionId;

    @Column(name = "knowledge_base_id", nullable = false, length = 64)
    private String knowledgeBaseId;

    /**
     * 任务执行阶段：PARSE, CHUNK, EMBED, INDEX, VALIDATE, COMPLETED
     */
    @Column(name = "job_stage", nullable = false, length = 32)
    private String jobStage = "PARSE";

    /**
     * 任务状态：PENDING, RUNNING, SUCCESS, FAILED, CANCELLED
     */
    @Column(nullable = false, length = 32)
    private String status = "PENDING";

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    private Integer attempt = 0;

    @Column(name = "max_attempts")
    private Integer maxAttempts = 3;

    @Column(name = "checkpoint_data", columnDefinition = "TEXT")
    private String checkpointData;

    @Column(name = "processed_documents")
    private Integer processedDocuments = 0;

    @Column(name = "total_documents")
    private Integer totalDocuments = 0;

    @Column(name = "processed_chunks")
    private Integer processedChunks = 0;

    @Column(name = "total_chunks")
    private Integer totalChunks = 0;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    private String lastErrorMessage;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = "kij-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        if (this.status == null || this.status.isBlank()) {
            this.status = "PENDING";
        }
        if (this.jobStage == null || this.jobStage.isBlank()) {
            this.jobStage = "PARSE";
        }
        if (this.attempt == null) {
            this.attempt = 0;
        }
        if (this.maxAttempts == null) {
            this.maxAttempts = 3;
        }
        if (this.processedDocuments == null) {
            this.processedDocuments = 0;
        }
        if (this.totalDocuments == null) {
            this.totalDocuments = 0;
        }
        if (this.processedChunks == null) {
            this.processedChunks = 0;
        }
        if (this.totalChunks == null) {
            this.totalChunks = 0;
        }
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public KnowledgeIndexJob() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getIndexVersionId() { return indexVersionId; }
    public void setIndexVersionId(String indexVersionId) { this.indexVersionId = indexVersionId; }

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }

    public String getJobStage() { return jobStage; }
    public void setJobStage(String jobStage) { this.jobStage = jobStage; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }

    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime leaseUntil) { this.leaseUntil = leaseUntil; }

    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }

    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }

    public String getCheckpointData() { return checkpointData; }
    public void setCheckpointData(String checkpointData) { this.checkpointData = checkpointData; }

    public Integer getProcessedDocuments() { return processedDocuments; }
    public void setProcessedDocuments(Integer processedDocuments) { this.processedDocuments = processedDocuments; }

    public Integer getTotalDocuments() { return totalDocuments; }
    public void setTotalDocuments(Integer totalDocuments) { this.totalDocuments = totalDocuments; }

    public Integer getProcessedChunks() { return processedChunks; }
    public void setProcessedChunks(Integer processedChunks) { this.processedChunks = processedChunks; }

    public Integer getTotalChunks() { return totalChunks; }
    public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }

    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }

    public String getLastErrorMessage() { return lastErrorMessage; }
    public void setLastErrorMessage(String lastErrorMessage) { this.lastErrorMessage = lastErrorMessage; }

    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(LocalDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
