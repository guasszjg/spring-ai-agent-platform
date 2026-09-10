package com.example.agentplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "open_api_call_logs")
public class OpenApiCallLog {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private LocalDateTime ts;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(name = "api_key_id", length = 64)
    private String apiKeyId;

    @Column(name = "client_credential_id", length = 64)
    private String clientCredentialId;

    @Column(name = "end_user", length = 128)
    private String endUser;

    @Column(name = "agent_id", length = 64)
    private String agentId;

    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Column(length = 64)
    private String endpoint;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "deny_reason", length = 64)
    private String denyReason;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(length = 64)
    private String model;

    @Column(length = 64)
    private String ip;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @PrePersist
    public void onCreate() {
        if (id == null || id.isBlank()) {
            id = "log-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        if (ts == null) {
            ts = LocalDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public LocalDateTime getTs() { return ts; }
    public void setTs(LocalDateTime ts) { this.ts = ts; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getApiKeyId() { return apiKeyId; }
    public void setApiKeyId(String apiKeyId) { this.apiKeyId = apiKeyId; }
    public String getClientCredentialId() { return clientCredentialId; }
    public void setClientCredentialId(String clientCredentialId) { this.clientCredentialId = clientCredentialId; }
    public String getEndUser() { return endUser; }
    public void setEndUser(String endUser) { this.endUser = endUser; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public String getDenyReason() { return denyReason; }
    public void setDenyReason(String denyReason) { this.denyReason = denyReason; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
