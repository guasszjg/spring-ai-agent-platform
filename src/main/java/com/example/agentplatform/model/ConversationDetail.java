package com.example.agentplatform.model;

import java.util.ArrayList;
import java.util.List;

public class ConversationDetail {

    private AgentConversation conversation;
    private List<AgentConversationMessage> messages = new ArrayList<>();

    public ConversationDetail() {
    }

    public ConversationDetail(AgentConversation conversation, List<AgentConversationMessage> messages) {
        this.conversation = conversation;
        this.messages = messages != null ? messages : new ArrayList<>();
    }

    public AgentConversation getConversation() {
        return conversation;
    }

    public void setConversation(AgentConversation conversation) {
        this.conversation = conversation;
    }

    public List<AgentConversationMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<AgentConversationMessage> messages) {
        this.messages = messages;
    }
}
