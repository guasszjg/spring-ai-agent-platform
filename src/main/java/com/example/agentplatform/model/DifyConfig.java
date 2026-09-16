package com.example.agentplatform.model;

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
 * Dify 外部知识引擎配置实体 (Dify External Knowledge Engine Configuration)
 */
@Entity
@Table(name = "dify_configs")
public class DifyConfig {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 500)
    private String baseUrl;

    @JsonIgnore
    @Column(name = "api_key_encrypted", nullable = false, columnDefinition = "TEXT")
    private String apiKeyEncrypted;

    @Column(length = 500)
    private String description;

    private Boolean isActive = false;

    private Boolean enabled = true;

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
            id = "dify-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (enabled == null) enabled = true;
        if (isActive == null) isActive = false;
        if (lastProbeStatus == null) lastProbeStatus = "UNTESTED";
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKeyEncrypted() { return apiKeyEncrypted; }
    public void setApiKeyEncrypted(String apiKeyEncrypted) { this.apiKeyEncrypted = apiKeyEncrypted; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

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
