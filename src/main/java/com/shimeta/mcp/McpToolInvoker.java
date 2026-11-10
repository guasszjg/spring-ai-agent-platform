package com.shimeta.mcp;

/** 把一次工具调用转发给 MCP 服务，返回交给模型的文本；失败时返回以「【MCP 工具失败】」开头的说明，不抛异常 */
public interface McpToolInvoker {
    String FAILURE_PREFIX = "【MCP 工具失败】";

    String call(McpToolSpec spec, String argumentsJson);
}
