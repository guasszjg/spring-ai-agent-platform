package com.shimeta.mcp;

public final class McpAuthType {
    public static final String NONE = "NONE";
    /** 请求头 Authorization: Bearer 加 Token */
    public static final String BEARER = "BEARER";
    /** 自定义请求头，JSON 对象 */
    public static final String HEADERS = "HEADERS";

    private McpAuthType() {
    }

    public static boolean isValid(String value) {
        return NONE.equals(value) || BEARER.equals(value) || HEADERS.equals(value);
    }
}
