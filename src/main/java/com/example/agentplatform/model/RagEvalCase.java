package com.example.agentplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "rag_eval_cases")
public class RagEvalCase {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String setId;

    @Column(nullable = false, length = 64)
    private String knowledgeBaseId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String query;

    @Column(columnDefinition = "TEXT")
    private String expectedChunkIds;

    @Column(columnDefinition = "TEXT")
    private String expectedDocumentIds;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null || id.isBlank()) {
            id = "ecase-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSetId() { return setId; }
    public void setSetId(String setId) { this.setId = setId; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getExpectedChunkIds() { return expectedChunkIds; }
    public void setExpectedChunkIds(String expectedChunkIds) { this.expectedChunkIds = expectedChunkIds; }
    public String getExpectedDocumentIds() { return expectedDocumentIds; }
    public void setExpectedDocumentIds(String expectedDocumentIds) { this.expectedDocumentIds = expectedDocumentIds; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
