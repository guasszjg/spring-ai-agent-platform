package com.example.agentplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_tool_secrets")
public class AgentToolSecret {

    @Id
    @Column(length = 64)
    private String agentId;

    @Column(columnDefinition = "TEXT")
    private String bochaApiKeyEncrypted;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getBochaApiKeyEncrypted() {
        return bochaApiKeyEncrypted;
    }

    public void setBochaApiKeyEncrypted(String bochaApiKeyEncrypted) {
        this.bochaApiKeyEncrypted = bochaApiKeyEncrypted;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
