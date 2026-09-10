package com.example.agentplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    @Column(name = "actor_type", nullable = false, length = 16)
    private String actorType;

    @Column(name = "actor_user_id", length = 64)
    private String actorUserId;

    @Column(name = "api_key_id", length = 64)
    private String apiKeyId;

    @Column(name = "client_credential_id", length = 64)
    private String clientCredentialId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "resource_type", length = 32)
    private String resourceType;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @Column(nullable = false, length = 16)
    private String result;

    @Column(name = "risk_level", length = 8)
    private String riskLevel = "LOW";

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "sanitized_diff", columnDefinition = "TEXT")
    private String sanitizedDiff;

    @PrePersist
    public void onCreate() {
        if (id == null || id.isBlank()) {
            id = "aud-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
        if (riskLevel == null) {
            riskLevel = "LOW";
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getActorType() { return actorType; }
    public void setActorType(String actorType) { this.actorType = actorType; }
    public String getActorUserId() { return actorUserId; }
    public void setActorUserId(String actorUserId) { this.actorUserId = actorUserId; }
    public String getApiKeyId() { return apiKeyId; }
    public void setApiKeyId(String apiKeyId) { this.apiKeyId = apiKeyId; }
    public String getClientCredentialId() { return clientCredentialId; }
    public void setClientCredentialId(String clientCredentialId) { this.clientCredentialId = clientCredentialId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getSanitizedDiff() { return sanitizedDiff; }
    public void setSanitizedDiff(String sanitizedDiff) { this.sanitizedDiff = sanitizedDiff; }
}
