package com.example.agentplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "alert_rules")
public class AlertRule {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "owner_id", length = 64)
    private String ownerId = "GLOBAL";

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 64)
    private String metric; // CALL_DENIED_RATE, HIGH_RISK_COUNT, QUOTA_EXCEEDED_COUNT

    @Column(nullable = false)
    private Double threshold;

    @Column(name = "time_window_minutes", nullable = false)
    private Integer timeWindowMinutes = 15;

    @Column(name = "webhook_url", length = 500)
    private String webhookUrl;

    @Column(name = "webhook_secret", length = 128)
    private String webhookSecret;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "silence_minutes", nullable = false)
    private Integer silenceMinutes = 30;

    @Column(name = "last_triggered_at")
    private LocalDateTime lastTriggeredAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        if (id == null || id.isBlank()) {
            id = "alert-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (enabled == null) enabled = true;
        if (silenceMinutes == null) silenceMinutes = 30;
        if (timeWindowMinutes == null) timeWindowMinutes = 15;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public Double getThreshold() { return threshold; }
    public void setThreshold(Double threshold) { this.threshold = threshold; }
    public Integer getTimeWindowMinutes() { return timeWindowMinutes; }
    public void setTimeWindowMinutes(Integer timeWindowMinutes) { this.timeWindowMinutes = timeWindowMinutes; }
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public Boolean getEnabled() { return enabled != null && enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getSilenceMinutes() { return silenceMinutes; }
    public void setSilenceMinutes(Integer silenceMinutes) { this.silenceMinutes = silenceMinutes; }
    public LocalDateTime getLastTriggeredAt() { return lastTriggeredAt; }
    public void setLastTriggeredAt(LocalDateTime lastTriggeredAt) { this.lastTriggeredAt = lastTriggeredAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
