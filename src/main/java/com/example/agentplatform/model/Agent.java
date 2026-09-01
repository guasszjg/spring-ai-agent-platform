package com.example.agentplatform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "agents")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Agent {
    @Id
    @Column(length = 64)
    private String id;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(unique = true, length = 100)
    private String code;
    @Column(length = 64)
    private String avatar;
    @Column(length = 64)
    private String category;
    @Column(columnDefinition = "TEXT")
    private String description;
    @Column(length = 100)
    private String modelName;
    @Column(columnDefinition = "TEXT")
    private String systemPrompt;
    private Double temperature;
    private Double topP;
    private Integer maxTokens;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_tags", joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "tag", length = 64)
    private List<String> tags = new ArrayList<>();
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private AgentStatus status;
    private Long callCount;
    private Double avgResponseTimeMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Agent() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.status = AgentStatus.RUNNING;
        this.callCount = 0L;
        this.avgResponseTimeMs = 350.0;
        this.temperature = 0.7;
        this.topP = 0.9;
        this.maxTokens = 2048;
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public AgentStatus getStatus() {
        return status;
    }

    public void setStatus(AgentStatus status) {
        this.status = status;
    }

    public Long getCallCount() {
        return callCount;
    }

    public void setCallCount(Long callCount) {
        this.callCount = callCount;
    }

    public Double getAvgResponseTimeMs() {
        return avgResponseTimeMs;
    }

    public void setAvgResponseTimeMs(Double avgResponseTimeMs) {
        this.avgResponseTimeMs = avgResponseTimeMs;
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

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = "agent-" + UUID.randomUUID().toString().substring(0, 8);
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = AgentStatus.RUNNING;
        }
        if (callCount == null) {
            callCount = 0L;
        }
        if (avgResponseTimeMs == null) {
            avgResponseTimeMs = 300.0;
        }
        if (avatar == null || avatar.isBlank()) {
            avatar = "🤖";
        }
        if (tags == null) {
            tags = new ArrayList<>();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
