package com.example.agentplatform.model;

import java.util.Map;

public class DashboardStats {
    private long totalAgents;
    private long runningAgents;
    private long idleAgents;
    private long disabledAgents;
    private long totalCalls;
    private double avgResponseTimeMs;
    private double successRate;
    private Map<String, Long> categoryDistribution;
    private Map<String, Long> modelDistribution;

    public DashboardStats() {
    }

    public long getTotalAgents() {
        return totalAgents;
    }

    public void setTotalAgents(long totalAgents) {
        this.totalAgents = totalAgents;
    }

    public long getRunningAgents() {
        return runningAgents;
    }

    public void setRunningAgents(long runningAgents) {
        this.runningAgents = runningAgents;
    }

    public long getIdleAgents() {
        return idleAgents;
    }

    public void setIdleAgents(long idleAgents) {
        this.idleAgents = idleAgents;
    }

    public long getDisabledAgents() {
        return disabledAgents;
    }

    public void setDisabledAgents(long disabledAgents) {
        this.disabledAgents = disabledAgents;
    }

    public long getTotalCalls() {
        return totalCalls;
    }

    public void setTotalCalls(long totalCalls) {
        this.totalCalls = totalCalls;
    }

    public double getAvgResponseTimeMs() {
        return avgResponseTimeMs;
    }

    public void setAvgResponseTimeMs(double avgResponseTimeMs) {
        this.avgResponseTimeMs = avgResponseTimeMs;
    }

    public double getSuccessRate() {
        return successRate;
    }

    public void setSuccessRate(double successRate) {
        this.successRate = successRate;
    }

    public Map<String, Long> getCategoryDistribution() {
        return categoryDistribution;
    }

    public void setCategoryDistribution(Map<String, Long> categoryDistribution) {
        this.categoryDistribution = categoryDistribution;
    }

    public Map<String, Long> getModelDistribution() {
        return modelDistribution;
    }

    public void setModelDistribution(Map<String, Long> modelDistribution) {
        this.modelDistribution = modelDistribution;
    }
}
