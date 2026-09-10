package com.example.agentplatform.model;

import com.example.agentplatform.config.StringListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "client_credentials", uniqueConstraints = {
        @UniqueConstraint(name = "uk_client_owner_type_hash", columnNames = {"owner_id", "client_type", "client_id_hash"})
})
public class ClientCredential {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Transient
    private String ownerUsername;

    @Column(name = "client_type", nullable = false, length = 32)
    private String clientType;

    @Column(name = "client_id", nullable = false, length = 191)
    private String clientId;

    @Column(name = "client_id_hash", nullable = false, length = 128)
    private String clientIdHash;

    @Column(length = 191)
    private String label;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "agent_scope", columnDefinition = "TEXT")
    private List<String> agentScope = new ArrayList<>();

    @Column(name = "rate_limit_rpm")
    private Integer rateLimitRpm;

    @Column(name = "daily_token_quota")
    private Long dailyTokenQuota;

    @Column(length = 16)
    private String status = "ACTIVE";

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "first_seen_at")
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "last_seen_ip", length = 64)
    private String lastSeenIp;

    @Column(columnDefinition = "TEXT")
    private String attributes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        if (id == null || id.isBlank()) {
            id = "cli-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = "ACTIVE";
        if (firstSeenAt == null) firstSeenAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }
    public String getClientType() { return clientType; }
    public void setClientType(String clientType) { this.clientType = clientType; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientIdHash() { return clientIdHash; }
    public void setClientIdHash(String clientIdHash) { this.clientIdHash = clientIdHash; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public List<String> getAgentScope() { return agentScope != null ? agentScope : new ArrayList<>(); }
    public void setAgentScope(List<String> agentScope) { this.agentScope = agentScope != null ? agentScope : new ArrayList<>(); }
    public Integer getRateLimitRpm() { return rateLimitRpm; }
    public void setRateLimitRpm(Integer rateLimitRpm) { this.rateLimitRpm = rateLimitRpm; }
    public Long getDailyTokenQuota() { return dailyTokenQuota; }
    public void setDailyTokenQuota(Long dailyTokenQuota) { this.dailyTokenQuota = dailyTokenQuota; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(LocalDateTime firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public String getLastSeenIp() { return lastSeenIp; }
    public void setLastSeenIp(String lastSeenIp) { this.lastSeenIp = lastSeenIp; }
    public String getAttributes() { return attributes; }
    public void setAttributes(String attributes) { this.attributes = attributes; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
