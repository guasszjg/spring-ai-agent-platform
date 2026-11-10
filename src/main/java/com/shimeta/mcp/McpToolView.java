package com.shimeta.mcp;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** MCP 工具 */
public class McpToolView {

    private String id;
    private String serverId;
    private String serverName;
    private String serverSlug;
    private String toolName;
    private String functionName;
    private String title;
    private String description;
    private Map<String, Object> inputSchema;
    private String category;
    private boolean writeOperation;
    private boolean enabled;
    private String reviewStatus;
    private LocalDateTime reviewedAt;
    /** 同一类别里已经启用的其它工具，用来提示功能重复 */
    private List<String> similarTools;
    /** 调用统计，只在管理视图里有 */
    private Long todayCalls;
    private Long monthCalls;
    private Long totalCalls;
    private Double monthSuccessRate;
    private Long monthAvgLatencyMs;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerSlug() {
        return serverSlug;
    }

    public void setServerSlug(String serverSlug) {
        this.serverSlug = serverSlug;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getFunctionName() {
        return functionName;
    }

    public void setFunctionName(String functionName) {
        this.functionName = functionName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(Map<String, Object> inputSchema) {
        this.inputSchema = inputSchema;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public boolean isWriteOperation() {
        return writeOperation;
    }

    public void setWriteOperation(boolean writeOperation) {
        this.writeOperation = writeOperation;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(String reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public List<String> getSimilarTools() {
        return similarTools;
    }

    public void setSimilarTools(List<String> similarTools) {
        this.similarTools = similarTools;
    }

    public Long getTodayCalls() {
        return todayCalls;
    }

    public void setTodayCalls(Long todayCalls) {
        this.todayCalls = todayCalls;
    }

    public Long getMonthCalls() {
        return monthCalls;
    }

    public void setMonthCalls(Long monthCalls) {
        this.monthCalls = monthCalls;
    }

    public Long getTotalCalls() {
        return totalCalls;
    }

    public void setTotalCalls(Long totalCalls) {
        this.totalCalls = totalCalls;
    }

    public Double getMonthSuccessRate() {
        return monthSuccessRate;
    }

    public void setMonthSuccessRate(Double monthSuccessRate) {
        this.monthSuccessRate = monthSuccessRate;
    }

    public Long getMonthAvgLatencyMs() {
        return monthAvgLatencyMs;
    }

    public void setMonthAvgLatencyMs(Long monthAvgLatencyMs) {
        this.monthAvgLatencyMs = monthAvgLatencyMs;
    }
}
