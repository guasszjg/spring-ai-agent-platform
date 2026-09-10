package com.example.agentplatform.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class OpenAgentDto {
    private String id;
    private String name;
    private String avatar;
    private String category;
    private String description;
    private String modelName;
    private String status;
    private Boolean isSystem;
    private String ownerUsername;
    private List<String> knowledgeBaseIds = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static OpenAgentDto fromEntity(Agent agent) {
        if (agent == null) return null;
        OpenAgentDto dto = new OpenAgentDto();
        dto.setId(agent.getId());
        dto.setName(agent.getName());
        dto.setAvatar(agent.getAvatar());
        dto.setCategory(agent.getCategory());
        dto.setDescription(agent.getDescription());
        dto.setModelName(agent.getModelName());
        dto.setStatus(agent.getStatus() != null ? agent.getStatus().name() : "RUNNING");
        dto.setIsSystem(Boolean.TRUE.equals(agent.getIsSystem()));
        dto.setOwnerUsername(agent.getOwnerUsername());
        dto.setKnowledgeBaseIds(agent.getKnowledgeBaseIds() != null ? new ArrayList<>(agent.getKnowledgeBaseIds()) : List.of());
        dto.setCreatedAt(agent.getCreatedAt());
        dto.setUpdatedAt(agent.getUpdatedAt());
        return dto;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Boolean getIsSystem() { return isSystem; }
    public void setIsSystem(Boolean isSystem) { this.isSystem = isSystem; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }
    public List<String> getKnowledgeBaseIds() { return knowledgeBaseIds; }
    public void setKnowledgeBaseIds(List<String> knowledgeBaseIds) { this.knowledgeBaseIds = knowledgeBaseIds; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
