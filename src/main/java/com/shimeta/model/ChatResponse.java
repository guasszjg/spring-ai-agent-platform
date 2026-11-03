package com.shimeta.model;

import java.time.LocalDateTime;

public class ChatResponse {
    private String agentId;
    private String agentName;
    private String reply;
    private Long latencyMs;
    private String model;
    private Integer tokensUsed;
    private String conversationId;
    private String toolCalled;
    private Boolean degraded;
    private String source;
    private LocalDateTime timestamp;
    /** 命中的 FAQ 回答附图（相对地址，如 /uploads/knowledge/xxx.png），没有时为空列表 */
    private java.util.List<String> images = java.util.List.of();

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

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getToolCalled() {
        return toolCalled;
    }

    public void setToolCalled(String toolCalled) {
        this.toolCalled = toolCalled;
    }

    public Boolean getDegraded() {
        return degraded;
    }

    public void setDegraded(Boolean degraded) {
        this.degraded = degraded;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public java.util.List<String> getImages() {
        return images;
    }

    public void setImages(java.util.List<String> images) {
        this.images = images == null ? java.util.List.of() : images;
    }
}
