package com.example.agentplatform.model;

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

    public java.util.Map<String, Object> getToolConfigs() {
        return toolConfigs;
    }

    public void setToolConfigs(java.util.Map<String, Object> toolConfigs) {
        this.toolConfigs = toolConfigs;
    }
}
