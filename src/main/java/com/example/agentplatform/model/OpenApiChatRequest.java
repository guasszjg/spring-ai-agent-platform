package com.example.agentplatform.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OpenApiChatRequest {

    private String message;

    @JsonProperty("response_mode")
    private String responseMode = "streaming";

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("agent_id")
    private String agentId;

    @JsonProperty("end_user")
    private String endUser;

    private String user;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getResponseMode() {
        return responseMode != null && !responseMode.isBlank() ? responseMode : "streaming";
    }

    public void setResponseMode(String responseMode) {
        this.responseMode = responseMode;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getEndUser() {
        return endUser;
    }

    public void setEndUser(String endUser) {
        this.endUser = endUser;
    }
}
