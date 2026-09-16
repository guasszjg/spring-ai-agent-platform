package com.example.agentplatform.model;

import java.time.LocalDateTime;

public class DifyConfigView {
    private String id;
    private String name;
    private String baseUrl;
    private String apiKeyMasked;
    private boolean hasApiKey;
    private String description;
    private Boolean isActive;
    private Boolean enabled;
    private String lastProbeStatus;
    private String lastProbeMessage;
    private LocalDateTime lastProbeAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static DifyConfigView from(DifyConfig entity, String plainKey) {
        DifyConfigView view = new DifyConfigView();
        view.setId(entity.getId());
        view.setName(entity.getName());
        view.setBaseUrl(entity.getBaseUrl());
        view.setDescription(entity.getDescription());
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
            if (plainKey.length() <= 10) {
                view.setApiKeyMasked("dataset-****");
            } else {
                view.setApiKeyMasked(plainKey.substring(0, 10) + "..." + plainKey.substring(plainKey.length() - 4));
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

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKeyMasked() { return apiKeyMasked; }
    public void setApiKeyMasked(String apiKeyMasked) { this.apiKeyMasked = apiKeyMasked; }

    public boolean isHasApiKey() { return hasApiKey; }
    public void setHasApiKey(boolean hasApiKey) { this.hasApiKey = hasApiKey; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

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
