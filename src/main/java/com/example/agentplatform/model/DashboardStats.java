package com.example.agentplatform.model;

import java.util.ArrayList;
import java.util.List;
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
    private long promptTokens;
    private long completionTokens;
    private double estimatedCostUsd;
    private double tokenChangePercent;
    private List<TrendPoint> tokenTrend = new ArrayList<>();
    private List<RankingItem> ranking = new ArrayList<>();
    private List<CategoryLatency> latencyByCategory = new ArrayList<>();

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

    public double getEstimatedCostUsd() {
        return estimatedCostUsd;
    }

    public void setEstimatedCostUsd(double estimatedCostUsd) {
        this.estimatedCostUsd = estimatedCostUsd;
    }

    public double getTokenChangePercent() {
        return tokenChangePercent;
    }

    public void setTokenChangePercent(double tokenChangePercent) {
        this.tokenChangePercent = tokenChangePercent;
    }

    public List<TrendPoint> getTokenTrend() {
        return tokenTrend;
    }

    public void setTokenTrend(List<TrendPoint> tokenTrend) {
        this.tokenTrend = tokenTrend;
    }

    public List<RankingItem> getRanking() {
        return ranking;
    }

    public void setRanking(List<RankingItem> ranking) {
        this.ranking = ranking;
    }

    public List<CategoryLatency> getLatencyByCategory() {
        return latencyByCategory;
    }

    public void setLatencyByCategory(List<CategoryLatency> latencyByCategory) {
        this.latencyByCategory = latencyByCategory;
    }

    public static class TrendPoint {
        private String label;
        private long promptTokens;
        private long completionTokens;

        public TrendPoint() {
        }

        public TrendPoint(String label, long promptTokens, long completionTokens) {
            this.label = label;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
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
    }

    public static class RankingItem {
        private int rank;
        private String avatar;
        private String name;
        private String model;
        private long calls;
        private long tokens;

        public RankingItem() {
        }

        public int getRank() {
            return rank;
        }

        public void setRank(int rank) {
            this.rank = rank;
        }

        public String getAvatar() {
            return avatar;
        }

        public void setAvatar(String avatar) {
            this.avatar = avatar;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public long getCalls() {
            return calls;
        }

        public void setCalls(long calls) {
            this.calls = calls;
        }

        public long getTokens() {
            return tokens;
        }

        public void setTokens(long tokens) {
            this.tokens = tokens;
        }
    }

    public static class CategoryLatency {
        private String category;
        private double avgLatencyMs;

        public CategoryLatency() {
        }

        public CategoryLatency(String category, double avgLatencyMs) {
            this.category = category;
            this.avgLatencyMs = avgLatencyMs;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public double getAvgLatencyMs() {
            return avgLatencyMs;
        }

        public void setAvgLatencyMs(double avgLatencyMs) {
            this.avgLatencyMs = avgLatencyMs;
        }
    }
}
