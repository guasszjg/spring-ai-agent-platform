package com.example.agentplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_conversation_messages")
public class AgentConversationMessage {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 64)
    private String conversationId;

    @Column(length = 32)
    private String role;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 120)
    private String model;

    private Long latencyMs;
    private Integer tokensUsed;
    private LocalDateTime createdAt;

    public AgentConversationMessage() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Integer getTokensUsed() {
        return tokensUsed;
    }

    public void setTokensUsed(Integer tokensUsed) {
        this.tokensUsed = tokensUsed;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = "msg-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
