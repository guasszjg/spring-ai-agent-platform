package com.example.agentplatform.model;

import java.util.List;

public class ChatRequest {
    private String agentId;
    private String message;
    private List<ChatMessage> history;
    private ChatGeneration generation;
    private String prompt;

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
}
