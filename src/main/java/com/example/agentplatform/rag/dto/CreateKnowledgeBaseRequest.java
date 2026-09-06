package com.example.agentplatform.rag.dto;

public class CreateKnowledgeBaseRequest {
    private String name;
    private String description;
    private String avatar = "📚";
    private String provider = "DIFY";
    private String indexingTechnique = "high_quality";
    private String permission = "only_me";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getIndexingTechnique() {
        return indexingTechnique;
    }

    public void setIndexingTechnique(String indexingTechnique) {
        this.indexingTechnique = indexingTechnique;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }
}
