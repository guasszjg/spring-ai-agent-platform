package com.example.agentplatform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "agent_templates")
public class AgentTemplate {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 64)
    private String category;

    @Column(length = 64)
    private String avatar;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    private Double temperature = 0.7;

    @Column(name = "top_p")
    private Double topP;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    @Column(columnDefinition = "TEXT")
    private String tags;

    @Column(name = "is_builtin")
    private Boolean isBuiltin = false;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    @Column(name = "owner_username", length = 64)
    private String ownerUsername;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public AgentTemplate() {
    }

    public AgentTemplate(String name, String category, String avatar, String description,
                         String systemPrompt, Double temperature, String tags, boolean isBuiltin, int sortOrder) {
        this.name = name;
        this.category = category;
        this.avatar = avatar;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.temperature = temperature;
        this.tags = tags;
        this.isBuiltin = isBuiltin;
        this.sortOrder = sortOrder;
    }

    @PrePersist
    public void onCreate() {
        if (id == null || id.isBlank()) {
            id = "tpl-" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (avatar == null || avatar.isBlank()) {
            avatar = "🤖";
        }
        if (temperature == null) {
            temperature = 0.7;
        }
        if (isBuiltin == null) {
            isBuiltin = false;
        }
        if (sortOrder == null) {
            sortOrder = 0;
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @JsonProperty(value = "tags", access = JsonProperty.Access.READ_ONLY)
    public List<String> getTagList() {
        if (tags == null || tags.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(tags.split("[,，]"))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());
    }

    public void setTagList(List<String> tagList) {
        if (tagList == null || tagList.isEmpty()) {
            this.tags = "";
        } else {
            this.tags = String.join(", ", tagList);
        }
    }

    @com.fasterxml.jackson.annotation.JsonSetter("tags")
    public void setTagsFromJson(Object value) {
        if (value == null) {
            this.tags = "";
            return;
        }
        if (value instanceof List<?> list) {
            setTagList(list.stream().map(String::valueOf).toList());
            return;
        }
        this.tags = String.valueOf(value);
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    @JsonProperty("isBuiltin")
    public Boolean getIsBuiltin() {
        return Boolean.TRUE.equals(isBuiltin);
    }

    @JsonProperty("isBuiltin")
    public void setIsBuiltin(Boolean isBuiltin) {
        this.isBuiltin = isBuiltin != null && isBuiltin;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerUsername() {
        return ownerUsername;
    }

    public void setOwnerUsername(String ownerUsername) {
        this.ownerUsername = ownerUsername;
    }
}
