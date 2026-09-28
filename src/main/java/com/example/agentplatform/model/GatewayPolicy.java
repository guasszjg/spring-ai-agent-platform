package com.example.agentplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "gateway_policies")
public class GatewayPolicy {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64)
    private String defaultProviderId;

    @Column(length = 64)
    private String fallbackProviderId;

    private Boolean failoverEnabled;
    private Integer timeoutMs;
    private Integer maxRetries;
    private LocalDateTime updatedAt;

    /** 平台 AI 助手专用通道：为空时助手走默认通道 */
    @Column(length = 64)
    private String assistantProviderId;

    /** 平台 AI 助手专用模型：为空时使用该通道的默认模型 */
    @Column(length = 128)
    private String assistantModel;

    public String getAssistantProviderId() {
        return assistantProviderId;
    }

    public void setAssistantProviderId(String assistantProviderId) {
        this.assistantProviderId = assistantProviderId;
    }

    public String getAssistantModel() {
        return assistantModel;
    }

    public void setAssistantModel(String assistantModel) {
        this.assistantModel = assistantModel;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDefaultProviderId() {
        return defaultProviderId;
    }

    public void setDefaultProviderId(String defaultProviderId) {
        this.defaultProviderId = defaultProviderId;
    }

    public String getFallbackProviderId() {
        return fallbackProviderId;
    }

    public void setFallbackProviderId(String fallbackProviderId) {
        this.fallbackProviderId = fallbackProviderId;
    }

    public Boolean getFailoverEnabled() {
        return failoverEnabled;
    }

    public void setFailoverEnabled(Boolean failoverEnabled) {
        this.failoverEnabled = failoverEnabled;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
        if (failoverEnabled == null) {
            failoverEnabled = true;
        }
        if (timeoutMs == null) {
            timeoutMs = 30000;
        }
        if (maxRetries == null) {
            maxRetries = 1;
        }
    }
}
