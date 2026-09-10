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

@Entity
@Table(name = "identity_providers")
public class IdentityProvider {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    private Boolean enabled = true;

    @Column(nullable = false, length = 24)
    private String direction = "BIDIRECTIONAL";

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    @Column(name = "auth_type", length = 24)
    private String authType = "BEARER";

    @JsonIgnore
    @Column(name = "credential_encrypted", columnDefinition = "TEXT")
    private String credentialEncrypted;

    @Column(name = "timeout_ms")
    private Integer timeoutMs = 8000;

    @Column(columnDefinition = "TEXT")
    private String operations = "{}";

    @Column(name = "field_mapping", columnDefinition = "TEXT")
    private String fieldMapping = "{}";

    @Column(name = "on_user_created", length = 24)
    private String onUserCreated = "OFF";

    @Column(name = "on_user_disabled", length = 24)
    private String onUserDisabled = "OFF";

    @Column(name = "fail_policy", length = 24)
    private String failPolicy = "ASYNC";

    @Column(name = "webhook_secret_encrypted", columnDefinition = "TEXT")
    @JsonIgnore
    private String webhookSecretEncrypted;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        if (id == null || id.isBlank()) {
            id = "idp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (enabled == null) enabled = true;
        if (direction == null) direction = "BIDIRECTIONAL";
        if (failPolicy == null) failPolicy = "ASYNC";
        if (onUserCreated == null) onUserCreated = "OFF";
        if (onUserDisabled == null) onUserDisabled = "OFF";
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Boolean getEnabled() { return Boolean.TRUE.equals(enabled); }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public String getCredentialEncrypted() { return credentialEncrypted; }
    public void setCredentialEncrypted(String credentialEncrypted) { this.credentialEncrypted = credentialEncrypted; }
    public Integer getTimeoutMs() { return timeoutMs != null ? timeoutMs : 8000; }
    public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }
    public String getOperations() { return operations; }
    public void setOperations(String operations) { this.operations = operations; }
    public String getFieldMapping() { return fieldMapping; }
    public void setFieldMapping(String fieldMapping) { this.fieldMapping = fieldMapping; }
    public String getOnUserCreated() { return onUserCreated != null ? onUserCreated : "OFF"; }
    public void setOnUserCreated(String onUserCreated) { this.onUserCreated = onUserCreated; }
    public String getOnUserDisabled() { return onUserDisabled != null ? onUserDisabled : "OFF"; }
    public void setOnUserDisabled(String onUserDisabled) { this.onUserDisabled = onUserDisabled; }
    public String getFailPolicy() { return failPolicy != null ? failPolicy : "ASYNC"; }
    public void setFailPolicy(String failPolicy) { this.failPolicy = failPolicy; }
    public String getWebhookSecretEncrypted() { return webhookSecretEncrypted; }
    public void setWebhookSecretEncrypted(String webhookSecretEncrypted) { this.webhookSecretEncrypted = webhookSecretEncrypted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
