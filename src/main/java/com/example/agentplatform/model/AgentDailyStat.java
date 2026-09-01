package com.example.agentplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;

@Entity
@Table(
        name = "agent_daily_stats",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_daily", columnNames = {"agent_id", "stat_date"})
)
public class AgentDailyStat {

    @Id
    @Column(length = 80)
    private String id;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    private long callCount;
    private long promptTokens;
    private long completionTokens;
    private long totalLatencyMs;
    private long successCount;

    public AgentDailyStat() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
    }

    public long getCallCount() {
        return callCount;
    }

    public void setCallCount(long callCount) {
        this.callCount = callCount;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public long getTotalLatencyMs() {
        return totalLatencyMs;
    }

    public void setTotalLatencyMs(long totalLatencyMs) {
        this.totalLatencyMs = totalLatencyMs;
    }

    public long getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(long successCount) {
        this.successCount = successCount;
    }
}
