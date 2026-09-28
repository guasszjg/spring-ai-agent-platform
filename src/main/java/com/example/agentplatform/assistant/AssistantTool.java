package com.example.agentplatform.assistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 平台 AI 助手可调用的一个工具。
 *
 * @param name        函数名（发给模型）
 * @param label       进行中的提示文案，如"查询智能体列表"，用于前端工具调用提示
 * @param description 工具说明（发给模型，写清适用场景）
 * @param parameters  JSON Schema 形式的参数定义
 * @param write       写工具：只生成待确认操作，不直接修改数据；只读观察员不会拿到写工具
 * @param superAdminOnly 仅超级管理员可用（其他角色不下发该工具）
 * @param handler     以当前用户身份执行，返回给模型的数据（必须是字段白名单，不含任何密钥）
 */
public record AssistantTool(String name,
                            String label,
                            String description,
                            Map<String, Object> parameters,
                            boolean write,
                            boolean superAdminOnly,
                            Handler handler) {

    @FunctionalInterface
    public interface Handler {
        Map<String, Object> handle(JsonNode args, ToolContext context);
    }

    public static AssistantTool read(String name, String label, String description,
                                     Map<String, Object> parameters, Handler handler) {
        return new AssistantTool(name, label, description, parameters, false, false, handler);
    }

    /** 仅超级管理员可用的只读工具 */
    public static AssistantTool adminRead(String name, String label, String description,
                                          Map<String, Object> parameters, Handler handler) {
        return new AssistantTool(name, label, description, parameters, false, true, handler);
    }

    public static AssistantTool write(String name, String label, String description,
                                      Map<String, Object> parameters, Handler handler) {
        return new AssistantTool(name, label, description, parameters, true, false, handler);
    }

    /** OpenAI 兼容的 function 工具定义。 */
    public Map<String, Object> definition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", parameters
                )
        );
    }
}
