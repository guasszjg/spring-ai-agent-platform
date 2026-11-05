package com.shimeta.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Rerank 重排模型配置。全平台同时只生效一个，自建知识库选择「Rerank 模型」模式时调用它。
 */
@Entity
@Table(name = "rerank_configs")
public class RerankConfig {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 500)
    private String baseUrl;

    @JsonIgnore
    @Column(name = "api_key_encrypted", columnDefinition = "TEXT")
    private String apiKeyEncrypted;

    /** 自定义请求头（JSON 对象，整体加密存储） */
    @JsonIgnore
    @Column(name = "custom_headers_encrypted", columnDefinition = "TEXT")
    private String customHeadersEncrypted;

    @Column(nullable = false, length = 120)
    private String modelName;

    private Boolean isActive = false;

    @Column(length = 32)
    private String lastProbeStatus = "UNTESTED";

    @Column(length = 500)
    private String lastProbeMessage;

    private LocalDateTime lastProbeAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null || id.isBlank()) {
            id = "rr-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (isActive == null) isActive = false;
        if (lastProbeStatus == null) lastProbeStatus = "UNTESTED";
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKeyEncrypted() { return apiKeyEncrypted; }
    public void setApiKeyEncrypted(String apiKeyEncrypted) { this.apiKeyEncrypted = apiKeyEncrypted; }

    public String getCustomHeadersEncrypted() { return customHeadersEncrypted; }
    public void setCustomHeadersEncrypted(String customHeadersEncrypted) { this.customHeadersEncrypted = customHeadersEncrypted; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public String getLastProbeStatus() { return lastProbeStatus; }
    public void setLastProbeStatus(String lastProbeStatus) { this.lastProbeStatus = lastProbeStatus; }

    public String getLastProbeMessage() { return lastProbeMessage; }
    public void setLastProbeMessage(String lastProbeMessage) { this.lastProbeMessage = lastProbeMessage; }

    public LocalDateTime getLastProbeAt() { return lastProbeAt; }
    public void setLastProbeAt(LocalDateTime lastProbeAt) { this.lastProbeAt = lastProbeAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
