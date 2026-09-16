package com.example.agentplatform.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

public class OpenApiChatRequest {

    @JsonProperty("message")
    @JsonAlias({"query", "prompt", "input", "content", "question"})
    private String message;

    @JsonProperty("response_mode")
    @JsonAlias({"responseMode", "mode"})
    private String responseMode = "streaming";

    @JsonProperty("conversation_id")
    @JsonAlias({"conversationId"})
    private String conversationId;

    @JsonProperty("agent_id")
    @JsonAlias({"agentId"})
    private String agentId;

    @JsonProperty("end_user")
    @JsonAlias({"endUser"})
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

    @JsonProperty("stream")
    public void setStream(Boolean stream) {
        if (Boolean.TRUE.equals(stream)) {
            this.responseMode = "streaming";
        } else if (Boolean.FALSE.equals(stream)) {
            this.responseMode = "blocking";
        }
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
