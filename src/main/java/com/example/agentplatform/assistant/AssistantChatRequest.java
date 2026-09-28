package com.example.agentplatform.assistant;

import java.util.Map;

/**
 * 助手流式对话请求。
 *
 * @param conversationId 为空则新建会话
 * @param message        用户消息
 * @param mode           CHAT 问答模式（不调用工具）/ AGENT 执行模式（默认）
 * @param context        页面上下文（P3）：page 当前页面，resourceType / resourceId 当前查看的资源（AGENT / KNOWLEDGE_BASE），可为空
 */
public record AssistantChatRequest(String conversationId, String message, String mode, Map<String, String> context) {

    public static final String MODE_CHAT = "CHAT";
    public static final String MODE_AGENT = "AGENT";

    public AssistantChatRequest(String conversationId, String message, String mode) {
        this(conversationId, message, mode, null);
    }

    public String normalizedMode() {
        return MODE_CHAT.equalsIgnoreCase(mode) ? MODE_CHAT : MODE_AGENT;
    }
}
