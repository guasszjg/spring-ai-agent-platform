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
 * GraphRAG 实体关系三元组 (Subject-Predicate-Object)
 * 遵循《Spring-AI自研RAG双引擎设计.md》P4 阶段：实体关系与知识图谱关联检索试点
 */
@Entity
@Table(name = "knowledge_graph_triplets", indexes = {
    @Index(name = "idx_kgt_kb_id", columnList = "knowledgeBaseId"),
    @Index(name = "idx_kgt_source", columnList = "sourceEntity"),
    @Index(name = "idx_kgt_target", columnList = "targetEntity")
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class KnowledgeGraphTriplet {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String knowledgeBaseId;

    @Column(nullable = false, length = 128)
    private String sourceEntity;

    @Column(nullable = false, length = 128)
    private String relation;

    @Column(nullable = false, length = 128)
    private String targetEntity;

    @Column(length = 64)
    private String sourceChunkId;

    private Double weight = 1.0;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (weight == null) {
            weight = 1.0;
        }
        createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }

    public String getSourceEntity() { return sourceEntity; }
    public void setSourceEntity(String sourceEntity) { this.sourceEntity = sourceEntity; }

    public String getRelation() { return relation; }
    public void setRelation(String relation) { this.relation = relation; }

    public String getTargetEntity() { return targetEntity; }
    public void setTargetEntity(String targetEntity) { this.targetEntity = targetEntity; }

    public String getSourceChunkId() { return sourceChunkId; }
    public void setSourceChunkId(String sourceChunkId) { this.sourceChunkId = sourceChunkId; }

    public Double getWeight() { return weight; }
    public void setWeight(Double weight) { this.weight = weight; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
