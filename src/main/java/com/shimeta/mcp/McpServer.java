package com.shimeta.mcp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "mcp_servers")
public class McpServer {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 24)
    private String slug;

    @Column(nullable = false, length = 1000)
    private String url;

    @JsonIgnore
    @Column(name = "url_encrypted", columnDefinition = "TEXT")
    private String urlEncrypted;

    @Column(nullable = false, length = 32)
    private String transport = McpTransportType.STREAMABLE_HTTP;

    @Column(name = "auth_type", nullable = false, length = 16)
    private String authType = McpAuthType.NONE;

    @JsonIgnore
    @Column(name = "auth_encrypted", columnDefinition = "TEXT")
    private String authEncrypted;

    @Column(name = "timeout_seconds", nullable = false)
    private Integer timeoutSeconds = 30;

    @Column(name = "allow_private_address", nullable = false)
    private Boolean allowPrivateAddress = false;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "last_status", length = 32)
    private String lastStatus;

    @Column(name = "last_message", length = 500)
    private String lastMessage;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(length = 500)
    private String remark;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

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

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUrlEncrypted() {
        return urlEncrypted;
    }

    public void setUrlEncrypted(String urlEncrypted) {
        this.urlEncrypted = urlEncrypted;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getAuthEncrypted() {
        return authEncrypted;
    }

    public void setAuthEncrypted(String authEncrypted) {
        this.authEncrypted = authEncrypted;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Boolean getAllowPrivateAddress() {
        return allowPrivateAddress;
    }

    public void setAllowPrivateAddress(Boolean allowPrivateAddress) {
        this.allowPrivateAddress = allowPrivateAddress;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(String lastStatus) {
        this.lastStatus = lastStatus;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    public LocalDateTime getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(LocalDateTime lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
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
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
