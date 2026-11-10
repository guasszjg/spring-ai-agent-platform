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
    private Integer promptTokens;
    /** 输入 Token 中命中提示词缓存的部分；通道不返回时为 0 */
    private Integer cachedPromptTokens;
    private Integer completionTokens;
    /** 本次主通道失败、改由备用通道回答时，主通道的名称；正常时为 null */
    private String failoverFrom;
    /** 本次对话模型加载了哪些技能 */
    private java.util.List<String> skillsLoaded = java.util.List.of();

    public java.util.List<String> getSkillsLoaded() {
        return skillsLoaded;
    }

    public void setSkillsLoaded(java.util.List<String> skillsLoaded) {
        this.skillsLoaded = skillsLoaded == null ? java.util.List.of() : skillsLoaded;
    }

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

    public Integer getCachedPromptTokens() {
        return cachedPromptTokens;
    }

    public void setCachedPromptTokens(Integer cachedPromptTokens) {
        this.cachedPromptTokens = cachedPromptTokens;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public String getFailoverFrom() {
        return failoverFrom;
    }

    public void setFailoverFrom(String failoverFrom) {
        this.failoverFrom = failoverFrom;
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
