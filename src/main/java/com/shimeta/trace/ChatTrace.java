package com.shimeta.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次智能体问答的完整调用链路快照（用于调试页实时/瀑布图分析及日志落库回溯）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatTrace {
    private String traceId;
    private String agentId;
    private String agentName;
    /** running | ok | error */
    private String status;
    private String model;
    private String channel;
    private String failoverFrom;
    private String routeNotice;
    private long startedAt;
    private long totalDurationMs;
    private Long firstTokenMs;
    private Integer promptTokens;
    private Integer cachedPromptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private String error;
    private List<ChatTraceStep> steps = new ArrayList<>();

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getFailoverFrom() {
        return failoverFrom;
    }

    public void setFailoverFrom(String failoverFrom) {
        this.failoverFrom = failoverFrom;
    }

    public String getRouteNotice() {
        return routeNotice;
    }

    public void setRouteNotice(String routeNotice) {
        this.routeNotice = routeNotice;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    public void setTotalDurationMs(long totalDurationMs) {
        this.totalDurationMs = totalDurationMs;
    }

    public Long getFirstTokenMs() {
        return firstTokenMs;
    }

    public void setFirstTokenMs(Long firstTokenMs) {
        this.firstTokenMs = firstTokenMs;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCachedPromptTokens() {
        return cachedPromptTokens;
    }

    public void setCachedPromptTokens(Integer cachedPromptTokens) {
        this.cachedPromptTokens = cachedPromptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public List<ChatTraceStep> getSteps() {
        return steps;
    }

    public void setSteps(List<ChatTraceStep> steps) {
        this.steps = steps == null ? new ArrayList<>() : steps;
    }
}
