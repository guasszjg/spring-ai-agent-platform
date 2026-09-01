package com.example.agentplatform.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "llm_providers")
public class LlmProvider {

    @Id
    @Column(length = 64)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LlmProviderType vendor;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String baseUrl;

    @JsonIgnore
    @Column(name = "api_key_encrypted", columnDefinition = "TEXT")
    private String apiKeyEncrypted;

    @Column(length = 120)
    private String defaultModel;

    @Column(columnDefinition = "TEXT")
    private String models;

    private Boolean enabled;
    private Boolean builtin;
    private Integer timeoutMs;
    private Integer maxRetries;

    @Column(length = 500)
    private String remark;

    @Column(length = 32)
    private String lastProbeStatus;

    @Column(length = 500)
    private String lastProbeMessage;

    private LocalDateTime lastProbeAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public LlmProviderType getVendor() {
        return vendor;
    }

    public void setVendor(LlmProviderType vendor) {
        this.vendor = vendor;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKeyEncrypted() {
        return apiKeyEncrypted;
    }

    public void setApiKeyEncrypted(String apiKeyEncrypted) {
        this.apiKeyEncrypted = apiKeyEncrypted;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public String getModels() {
        return models;
    }

    public void setModels(String models) {
        this.models = models;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getBuiltin() {
        return builtin;
    }

    public void setBuiltin(Boolean builtin) {
        this.builtin = builtin;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getLastProbeStatus() {
        return lastProbeStatus;
    }

    public void setLastProbeStatus(String lastProbeStatus) {
        this.lastProbeStatus = lastProbeStatus;
    }

    public String getLastProbeMessage() {
        return lastProbeMessage;
    }

    public void setLastProbeMessage(String lastProbeMessage) {
        this.lastProbeMessage = lastProbeMessage;
    }

    public LocalDateTime getLastProbeAt() {
        return lastProbeAt;
    }

    public void setLastProbeAt(LocalDateTime lastProbeAt) {
        this.lastProbeAt = lastProbeAt;
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
            id = "llm-" + UUID.randomUUID().toString().substring(0, 8);
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (enabled == null) {
            enabled = false;
        }
        if (builtin == null) {
            builtin = false;
        }
        if (timeoutMs == null) {
            timeoutMs = 30000;
        }
        if (maxRetries == null) {
            maxRetries = 1;
        }
        if (lastProbeStatus == null) {
            lastProbeStatus = "UNTESTED";
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
