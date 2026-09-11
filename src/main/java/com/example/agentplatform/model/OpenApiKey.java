package com.example.agentplatform.model;

import com.example.agentplatform.config.StringListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "open_api_keys")
public class OpenApiKey {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "issuer_auth_version")
    private Integer issuerAuthVersion = 1;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "key_prefix", nullable = false, length = 24)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, unique = true, length = 128)
    private String keyHash;

    @Convert(converter = StringListJsonConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private List<String> scopes = new ArrayList<>();

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "agent_scope", columnDefinition = "TEXT")
    private List<String> agentScope = new ArrayList<>();

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "ip_allowlist", columnDefinition = "TEXT")
    private List<String> ipAllowlist = new ArrayList<>();

    @Column(name = "rate_limit_rpm")
    private Integer rateLimitRpm;

    @Column(name = "daily_token_quota")
    private Long dailyTokenQuota;

    @Column(length = 16)
    private String status = "ACTIVE";

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "grace_expires_at")
    private LocalDateTime graceExpiresAt;

    @Column(name = "rotated_to_key_id", length = 64)
    private String rotatedToKeyId;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "last_used_ip", length = 64)
    private String lastUsedIp;

    private Boolean migrated = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        if (id == null || id.isBlank()) {
            id = "oak-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = "ACTIVE";
        }
        if (issuerAuthVersion == null) {
            issuerAuthVersion = 1;
        }
        if (migrated == null) {
            migrated = false;
        }
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Integer getIssuerAuthVersion() { return issuerAuthVersion != null ? issuerAuthVersion : 1; }
    public void setIssuerAuthVersion(Integer issuerAuthVersion) { this.issuerAuthVersion = issuerAuthVersion; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
    public List<String> getScopes() { return scopes != null ? scopes : new ArrayList<>(); }
    public void setScopes(List<String> scopes) { this.scopes = scopes != null ? scopes : new ArrayList<>(); }
    public List<String> getAgentScope() { return agentScope != null ? agentScope : new ArrayList<>(); }
    public void setAgentScope(List<String> agentScope) { this.agentScope = agentScope != null ? agentScope : new ArrayList<>(); }
    public List<String> getIpAllowlist() { return ipAllowlist != null ? ipAllowlist : new ArrayList<>(); }
    public void setIpAllowlist(List<String> ipAllowlist) { this.ipAllowlist = ipAllowlist != null ? ipAllowlist : new ArrayList<>(); }
    public Integer getRateLimitRpm() { return rateLimitRpm; }
    public void setRateLimitRpm(Integer rateLimitRpm) { this.rateLimitRpm = rateLimitRpm; }
    public Long getDailyTokenQuota() { return dailyTokenQuota; }
    public void setDailyTokenQuota(Long dailyTokenQuota) { this.dailyTokenQuota = dailyTokenQuota; }
    public String getStatus() { return status != null ? status : "ACTIVE"; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }
    public LocalDateTime getGraceExpiresAt() { return graceExpiresAt; }
    public void setGraceExpiresAt(LocalDateTime graceExpiresAt) { this.graceExpiresAt = graceExpiresAt; }
    public String getRotatedToKeyId() { return rotatedToKeyId; }
    public void setRotatedToKeyId(String rotatedToKeyId) { this.rotatedToKeyId = rotatedToKeyId; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public String getLastUsedIp() { return lastUsedIp; }
    public void setLastUsedIp(String lastUsedIp) { this.lastUsedIp = lastUsedIp; }
    public Boolean getMigrated() { return Boolean.TRUE.equals(migrated); }
    public void setMigrated(Boolean migrated) { this.migrated = migrated; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
