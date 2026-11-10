package com.shimeta.mcp;

/** MCP 传输方式。用字符串常量而不是枚举列，避免 Hibernate 给列加检查约束 */
public final class McpTransportType {
    public static final String STREAMABLE_HTTP = "STREAMABLE_HTTP";
    public static final String SSE = "SSE";

    private McpTransportType() {
    }

    public static boolean isValid(String value) {
        return STREAMABLE_HTTP.equals(value) || SSE.equals(value);
    }
}
