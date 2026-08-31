package com.example.agentplatform.model;

import java.time.LocalDateTime;

public class ChatResponse {
    private String agentId;
    private String agentName;
    private String reply;
    private Long latencyMs;
    private String model;
    private Integer tokensUsed;
    private LocalDateTime timestamp;

    public ChatResponse() {
        this.timestamp = LocalDateTime.now();
    }

    public ChatResponse(String agentId, String agentName, String reply, Long latencyMs, String model, Integer tokensUsed) {
        this.agentId = agentId;
        this.agentName = agentName;
        this.reply = reply;
        this.latencyMs = latencyMs;
        this.model = model;
        this.tokensUsed = tokensUsed;
        this.timestamp = LocalDateTime.now();
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getTokensUsed() {
        return tokensUsed;
    }

    public void setTokensUsed(Integer tokensUsed) {
        this.tokensUsed = tokensUsed;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
