package com.example.agentplatform.model;

import java.util.ArrayList;
import java.util.List;

public class AgentMonitorStats {

    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private double tokenChangePercent;
    private long sessionCount;
    private long messageCount;
    private long callCount;
    private double avgResponseTimeMs;
    private double estimatedCostUsd;
    private List<DashboardStats.TrendPoint> tokenTrend = new ArrayList<>();

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

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public double getTokenChangePercent() {
        return tokenChangePercent;
    }

    public void setTokenChangePercent(double tokenChangePercent) {
        this.tokenChangePercent = tokenChangePercent;
    }

    public long getSessionCount() {
        return sessionCount;
    }

    public void setSessionCount(long sessionCount) {
        this.sessionCount = sessionCount;
    }

    public long getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(long messageCount) {
        this.messageCount = messageCount;
    }

    public long getCallCount() {
        return callCount;
    }

    public void setCallCount(long callCount) {
        this.callCount = callCount;
    }

    public double getAvgResponseTimeMs() {
        return avgResponseTimeMs;
    }

    public void setAvgResponseTimeMs(double avgResponseTimeMs) {
        this.avgResponseTimeMs = avgResponseTimeMs;
    }

    public double getEstimatedCostUsd() {
        return estimatedCostUsd;
    }

    public void setEstimatedCostUsd(double estimatedCostUsd) {
        this.estimatedCostUsd = estimatedCostUsd;
    }

    public List<DashboardStats.TrendPoint> getTokenTrend() {
        return tokenTrend;
    }

    public void setTokenTrend(List<DashboardStats.TrendPoint> tokenTrend) {
        this.tokenTrend = tokenTrend;
    }
}
