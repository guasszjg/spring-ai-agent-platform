package com.shimeta.assistant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 待确认操作卡片的确认请求体（4.4 执行能力）：
 * <ul>
 *   <li>{@code confirmName}：D 级高危卡片必填，必须与卡片展示的目标资源名称完全一致；</li>
 *   <li>{@code secretValue}：修改网关密钥、重置用户密码等场景由用户在卡片密码框填写并直传后端，
 *       不经过模型、不落库、不进对话记录与审计日志。</li>
 * </ul>
 */
public record AssistantActionConfirmRequest(String confirmName, String secretValue, JsonNode formData) {
    public AssistantActionConfirmRequest(String confirmName, String secretValue) {
        this(confirmName, secretValue, null);
    }
}
