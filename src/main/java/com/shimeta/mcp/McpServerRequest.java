package com.shimeta.mcp;

/** 添加或修改 MCP 服务。修改时没传的字段保持不变 */
public class McpServerRequest {

    private String name;
    /** 工具名前缀里的短标识，只在创建时有效；不填按名称生成 */
    private String slug;
    /** 修改时留空或原样传回打码后的地址表示不改 */
    private String url;
    /** STREAMABLE_HTTP / SSE */
    private String transport;
    /** NONE / BEARER / HEADERS */
    private String authType;
    /** BEARER 填 Token，HEADERS 填请求头 JSON；修改时留空表示不改 */
    private String authSecret;
    private Integer timeoutSeconds;
    private Boolean allowPrivateAddress;
    private Boolean enabled;
    private String remark;

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

    public String getAuthSecret() {
        return authSecret;
    }

    public void setAuthSecret(String authSecret) {
        this.authSecret = authSecret;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Boolean getAllowPrivateAddress() {
        return allowPrivateAddress;
    }

    public void setAllowPrivateAddress(Boolean allowPrivateAddress) {
        this.allowPrivateAddress = allowPrivateAddress;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
