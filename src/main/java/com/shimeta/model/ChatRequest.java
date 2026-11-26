package com.shimeta.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

public class ChatRequest {
    private String agentId;
    private String message;
    private List<ChatMessage> history;
    private ChatGeneration generation;
    private String prompt;
    private String conversationId;
    private String account;
    private List<String> enabledTools;
    private java.util.Map<String, Object> toolConfigs;
    /** 本轮对话的自定义提示词变量值（key -> value），未传的变量按默认值回退 */
    private java.util.Map<String, Object> inputs;
    /** 是否采集调用链路详情：调试页默认开启（可由前端关闭），开放接口按平台开关决定（默认关闭） */
    private Boolean traceEnabled;

    public ChatRequest() {
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<ChatMessage> getHistory() {
        return history;
    }

    public void setHistory(List<ChatMessage> history) {
        this.history = history;
    }

    public ChatGeneration getGeneration() {
        return generation;
    }

    public void setGeneration(ChatGeneration generation) {
        this.generation = generation;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    /**
     * 服务端预先分配的新会话 ID（开放接口流式输出需要在第一段内容里就带上会话 ID）。
     * 只由服务端设置，不接受客户端传入；conversationId 指向已有会话时不使用。
     */
    @JsonIgnore
    private transient String newConversationId;

    @JsonIgnore
    public String getNewConversationId() {
        return newConversationId;
    }

    @JsonIgnore
    public void setNewConversationId(String newConversationId) {
        this.newConversationId = newConversationId;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public List<String> getEnabledTools() {
        return enabledTools;
    }

    public void setEnabledTools(List<String> enabledTools) {
        this.enabledTools = enabledTools;
    }

    /**
     * 调试页本窗口的通道选择（未发布的草稿），只对这次请求生效；为空时用智能体已发布的设置。
     * 只有控制台调试接口接收，开放接口自己构造请求，不会带它
     */
    private AgentRouteChoice routeDraft;

    public AgentRouteChoice getRouteDraft() {
        return routeDraft;
    }

    public void setRouteDraft(AgentRouteChoice routeDraft) {
        this.routeDraft = routeDraft;
    }

    public java.util.Map<String, Object> getToolConfigs() {
        return toolConfigs;
    }

    public void setToolConfigs(java.util.Map<String, Object> toolConfigs) {
        this.toolConfigs = toolConfigs;
    }

    public java.util.Map<String, Object> getInputs() {
        return inputs;
    }

    public void setInputs(java.util.Map<String, Object> inputs) {
        this.inputs = inputs;
    }

    public Boolean getTraceEnabled() {
        return traceEnabled;
    }

    public void setTraceEnabled(Boolean traceEnabled) {
        this.traceEnabled = traceEnabled;
    }
}
