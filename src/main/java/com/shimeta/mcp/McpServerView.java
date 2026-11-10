package com.shimeta.mcp;

import java.time.LocalDateTime;
import java.util.List;

/** MCP 服务（不含密钥） */
public class McpServerView {

    private String id;
    private String name;
    private String slug;
    /** 查询参数的值已打码 */
    private String url;
    private String transport;
    private String authType;
    private boolean hasAuth;
    private Integer timeoutSeconds;
    private boolean allowPrivateAddress;
    private boolean enabled;
    private String lastStatus;
    private String lastMessage;
    private LocalDateTime lastCheckedAt;
    private String remark;
    private LocalDateTime updatedAt;
    private int toolCount;
    private int enabledToolCount;
    /** 待审核或上游有变化的工具数 */
    private int pendingToolCount;
    private List<McpToolView> tools;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public boolean isHasAuth() {
        return hasAuth;
    }

    public void setHasAuth(boolean hasAuth) {
        this.hasAuth = hasAuth;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isAllowPrivateAddress() {
        return allowPrivateAddress;
    }

    public void setAllowPrivateAddress(boolean allowPrivateAddress) {
        this.allowPrivateAddress = allowPrivateAddress;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(String lastStatus) {
        this.lastStatus = lastStatus;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    public LocalDateTime getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(LocalDateTime lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getToolCount() {
        return toolCount;
    }

    public void setToolCount(int toolCount) {
        this.toolCount = toolCount;
    }

    public int getEnabledToolCount() {
        return enabledToolCount;
    }

    public void setEnabledToolCount(int enabledToolCount) {
        this.enabledToolCount = enabledToolCount;
    }

    public int getPendingToolCount() {
        return pendingToolCount;
    }

    public void setPendingToolCount(int pendingToolCount) {
        this.pendingToolCount = pendingToolCount;
    }

    public List<McpToolView> getTools() {
        return tools;
    }

    public void setTools(List<McpToolView> tools) {
        this.tools = tools;
    }
}
