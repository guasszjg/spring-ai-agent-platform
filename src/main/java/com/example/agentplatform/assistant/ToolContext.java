package com.example.agentplatform.assistant;

import com.example.agentplatform.model.AssistantAction;
import com.example.agentplatform.security.CurrentActor;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次工具调用的上下文。
 *
 * @param actor          当前登录用户，工具以其身份与权限执行
 * @param conversationId 所在会话；写工具生成的待确认操作归属于该会话
 * @param createdActions 本轮对话中写工具生成的待确认操作，供对话流程推送操作卡片
 */
public record ToolContext(CurrentActor actor, String conversationId, List<AssistantAction> createdActions) {

    public static ToolContext of(CurrentActor actor, String conversationId) {
        return new ToolContext(actor, conversationId, new ArrayList<>());
    }
}
