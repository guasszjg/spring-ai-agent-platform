package com.example.agentplatform.assistant;

import com.example.agentplatform.security.CurrentActor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 平台 AI 助手可调用的一个工具。
 *
 * @param name        函数名（发给模型）
 * @param label       进行中的提示文案，如"查询智能体列表"，用于前端工具调用提示
 * @param description 工具说明（发给模型，写清适用场景）
 * @param parameters  JSON Schema 形式的参数定义
 * @param handler     以当前用户身份执行，返回给模型的数据（必须是字段白名单，不含任何密钥）
 */
public record AssistantTool(String name,
                            String label,
                            String description,
                            Map<String, Object> parameters,
                            Handler handler) {

    @FunctionalInterface
    public interface Handler {
        Map<String, Object> handle(JsonNode args, CurrentActor actor);
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
