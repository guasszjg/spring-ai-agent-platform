package com.shimeta.mcp;

import java.util.Map;

/**
 * 对话时可用的一个 MCP 工具（已启用、已审核）。
 *
 * @param functionName 发给模型的函数名，平台内唯一
 * @param toolName     MCP 服务里的原始工具名
 * @param inputSchema  参数的 JSON Schema
 */
public record McpToolSpec(String functionName, String serverId, String serverName, String toolName, String title,
                          String description, Map<String, Object> inputSchema, boolean writeOperation) {
}
