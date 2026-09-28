package com.example.agentplatform.assistant;

/**
 * 助手流式对话请求。
 *
 * @param conversationId 为空则新建会话
 * @param message        用户消息
 * @param mode           CHAT 问答模式（不调用工具）/ AGENT 执行模式（默认）
 */
public record AssistantChatRequest(String conversationId, String message, String mode) {

    public static final String MODE_CHAT = "CHAT";
    public static final String MODE_AGENT = "AGENT";

    public String normalizedMode() {
        return MODE_CHAT.equalsIgnoreCase(mode) ? MODE_CHAT : MODE_AGENT;
    }
}
