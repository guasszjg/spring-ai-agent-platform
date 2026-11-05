package com.shimeta.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "rag_eval_runs")
public class RagEvalRun {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String setId;

    @Column(nullable = false, length = 64)
    private String knowledgeBaseId;

    @Column(nullable = false, length = 32)
    private String engine;

    private Integer caseCount = 0;
    private Double hitAt5;
    private Double recallAt10;
    private Double ndcgAt10;
    private Double avgLatencyMs;

    @Column(columnDefinition = "TEXT")
    private String detailJson;

    @Column(length = 200)
    private String label;

    @Column(columnDefinition = "TEXT")
    private String configJson;

    @Column(length = 64)
    private String casesFingerprint;

    @Column(nullable = false, length = 16)
    private String status = "DONE";

    @Column(nullable = false)
    private Integer completedCount = 0;

    @Column(nullable = false)
    private Integer errorCount = 0;

    @Column(nullable = false)
    private Integer rerankFallbackCount = 0;

    @Column(length = 500)
    private String errorMessage;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null || id.isBlank()) {
            id = "erun-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSetId() { return setId; }
    public void setSetId(String setId) { this.setId = setId; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public Integer getCaseCount() { return caseCount; }
    public void setCaseCount(Integer caseCount) { this.caseCount = caseCount; }
    public Double getHitAt5() { return hitAt5; }
    public void setHitAt5(Double hitAt5) { this.hitAt5 = hitAt5; }
    public Double getRecallAt10() { return recallAt10; }
    public void setRecallAt10(Double recallAt10) { this.recallAt10 = recallAt10; }
    public Double getNdcgAt10() { return ndcgAt10; }
    public void setNdcgAt10(Double ndcgAt10) { this.ndcgAt10 = ndcgAt10; }
    public Double getAvgLatencyMs() { return avgLatencyMs; }
    public void setAvgLatencyMs(Double avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public String getCasesFingerprint() { return casesFingerprint; }
    public void setCasesFingerprint(String casesFingerprint) { this.casesFingerprint = casesFingerprint; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getCompletedCount() { return completedCount; }
    public void setCompletedCount(Integer completedCount) { this.completedCount = completedCount; }

    public Integer getErrorCount() { return errorCount; }
    public void setErrorCount(Integer errorCount) { this.errorCount = errorCount; }

    public Integer getRerankFallbackCount() { return rerankFallbackCount; }
    public void setRerankFallbackCount(Integer rerankFallbackCount) { this.rerankFallbackCount = rerankFallbackCount; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
