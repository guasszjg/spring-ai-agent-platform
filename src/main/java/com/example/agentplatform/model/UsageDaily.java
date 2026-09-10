package com.example.agentplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "usage_daily",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_usage_daily",
                columnNames = {"stat_date", "owner_id", "agent_id", "api_key_id", "client_credential_id"}
        )
)
public class UsageDaily {

    @Id
    @Column(length = 120)
    private String id;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId = "";

    @Column(name = "api_key_id", nullable = false, length = 64)
    private String apiKeyId = "";

    @Column(name = "client_credential_id", nullable = false, length = 64)
    private String clientCredentialId = "";

    @Column(nullable = false)
    private long calls = 0;

    @Column(name = "chat_calls", nullable = false)
    private long chatCalls = 0;

    @Column(nullable = false)
    private long messages = 0;

    @Column(nullable = false)
    private long conversations = 0;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens = 0;

    @Column(nullable = false)
    private long denied = 0;

    @Column(nullable = false)
    private long errors = 0;

    @Column(name = "latency_sum_ms", nullable = false)
    private long latencySumMs = 0;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void onSave() {
        this.updatedAt = LocalDateTime.now();
    }

    public static String buildId(LocalDate date, String ownerId, String agentId, String keyId, String clientId) {
        String safeAgent = (agentId != null && !agentId.isBlank()) ? agentId : "ALL";
        String safeKey = (keyId != null && !keyId.isBlank()) ? keyId : "ALL";
        String safeClient = (clientId != null && !clientId.isBlank()) ? clientId : "ALL";
        return date.toString() + "_" + ownerId + "_" + safeAgent + "_" + safeKey + "_" + safeClient;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId != null ? agentId : ""; }
    public String getApiKeyId() { return apiKeyId; }
    public void setApiKeyId(String apiKeyId) { this.apiKeyId = apiKeyId != null ? apiKeyId : ""; }
    public String getClientCredentialId() { return clientCredentialId; }
    public void setClientCredentialId(String clientCredentialId) { this.clientCredentialId = clientCredentialId != null ? clientCredentialId : ""; }
    public long getCalls() { return calls; }
    public void setCalls(long calls) { this.calls = calls; }
    public long getChatCalls() { return chatCalls; }
    public void setChatCalls(long chatCalls) { this.chatCalls = chatCalls; }
    public long getMessages() { return messages; }
    public void setMessages(long messages) { this.messages = messages; }
    public long getConversations() { return conversations; }
    public void setConversations(long conversations) { this.conversations = conversations; }
    public long getPromptTokens() { return promptTokens; }
    public void setPromptTokens(long promptTokens) { this.promptTokens = promptTokens; }
    public long getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(long completionTokens) { this.completionTokens = completionTokens; }
    public long getDenied() { return denied; }
    public void setDenied(long denied) { this.denied = denied; }
    public long getErrors() { return errors; }
    public void setErrors(long errors) { this.errors = errors; }
    public long getLatencySumMs() { return latencySumMs; }
    public void setLatencySumMs(long latencySumMs) { this.latencySumMs = latencySumMs; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
