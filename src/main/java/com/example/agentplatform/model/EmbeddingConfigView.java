package com.example.agentplatform.model;

import java.time.LocalDateTime;

public class EmbeddingConfigView {
    private String id;
    private String name;
    private String provider;
    private String baseUrl;
    private String apiKeyMasked;
    private boolean hasApiKey;
    private String modelName;
    private Integer dimension;
    private Boolean isActive;
    private Boolean enabled;
    private String lastProbeStatus;
    private String lastProbeMessage;
    private LocalDateTime lastProbeAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static EmbeddingConfigView from(EmbeddingConfig entity, String plainKey) {
        EmbeddingConfigView view = new EmbeddingConfigView();
        view.setId(entity.getId());
        view.setName(entity.getName());
        view.setProvider(entity.getProvider());
        view.setBaseUrl(entity.getBaseUrl());
        view.setModelName(entity.getModelName());
        view.setDimension(entity.getDimension());
        view.setIsActive(entity.getIsActive() != null && entity.getIsActive());
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled());
        view.setLastProbeStatus(entity.getLastProbeStatus());
        view.setLastProbeMessage(entity.getLastProbeMessage());
        view.setLastProbeAt(entity.getLastProbeAt());
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());

        boolean hasKey = plainKey != null && !plainKey.isBlank();
        view.setHasApiKey(hasKey);
        if (hasKey) {
            if (plainKey.length() <= 8) {
                view.setApiKeyMasked("****");
            } else {
                view.setApiKeyMasked(plainKey.substring(0, 3) + "..." + plainKey.substring(plainKey.length() - 4));
            }
        } else {
            view.setApiKeyMasked("");
        }
        return view;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKeyMasked() { return apiKeyMasked; }
    public void setApiKeyMasked(String apiKeyMasked) { this.apiKeyMasked = apiKeyMasked; }

    public boolean isHasApiKey() { return hasApiKey; }
    public void setHasApiKey(boolean hasApiKey) { this.hasApiKey = hasApiKey; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public Integer getDimension() { return dimension; }
    public void setDimension(Integer dimension) { this.dimension = dimension; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getLastProbeStatus() { return lastProbeStatus; }
    public void setLastProbeStatus(String lastProbeStatus) { this.lastProbeStatus = lastProbeStatus; }

    public String getLastProbeMessage() { return lastProbeMessage; }
    public void setLastProbeMessage(String lastProbeMessage) { this.lastProbeMessage = lastProbeMessage; }

    public LocalDateTime getLastProbeAt() { return lastProbeAt; }
    public void setLastProbeAt(LocalDateTime lastProbeAt) { this.lastProbeAt = lastProbeAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
