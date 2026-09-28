package com.example.agentplatform.assistant;

import com.example.agentplatform.model.AssistantAction;
import com.example.agentplatform.security.CurrentActor;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次对话中工具调用的上下文。
 *
 * @param actor          当前登录用户，工具以其身份与权限执行
 * @param conversationId 所在会话；写工具生成的待确认操作归属于该会话
 * @param createdActions 本轮对话中写工具生成的待确认操作，供对话流程推送操作卡片
 * @param calls          本轮对话中模型发起的全部工具调用（含参数），供评测检查"是否选对工具、参数是否正确"
 */
public record ToolContext(CurrentActor actor, String conversationId, List<AssistantAction> createdActions,
                          List<ToolCallRecord> calls) {

    public static ToolContext of(CurrentActor actor, String conversationId) {
        return new ToolContext(actor, conversationId, new ArrayList<>(), new ArrayList<>());
    }

    /** 一次工具调用：工具名、模型给出的参数（JSON 原文）与是否成功。 */
    public record ToolCallRecord(String name, String arguments, boolean ok) {
    }
}
