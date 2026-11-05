package com.shimeta.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class RerankConfigView {
    private String id;
    private String name;
    private String baseUrl;
    private String apiKeyMasked;
    private String modelName;
    private Boolean isActive;
    private String lastProbeStatus;
    private String lastProbeMessage;
    private LocalDateTime lastProbeAt;
    /** 已保存的自定义请求头名称（值不返回） */
    private List<String> customHeaderNames = new ArrayList<>();

    public static RerankConfigView from(RerankConfig entity, String plainKey, Collection<String> headerNames) {
        RerankConfigView view = new RerankConfigView();
        view.id = entity.getId();
        view.name = entity.getName();
        view.baseUrl = entity.getBaseUrl();
        view.modelName = entity.getModelName();
        view.isActive = Boolean.TRUE.equals(entity.getIsActive());
        view.lastProbeStatus = entity.getLastProbeStatus();
        view.lastProbeMessage = entity.getLastProbeMessage();
        view.lastProbeAt = entity.getLastProbeAt();
        view.customHeaderNames = headerNames == null ? new ArrayList<>() : new ArrayList<>(headerNames);
        if (plainKey == null || plainKey.isBlank()) {
            view.apiKeyMasked = "";
        } else if (plainKey.length() <= 8) {
            view.apiKeyMasked = "****";
        } else {
            view.apiKeyMasked = plainKey.substring(0, 3) + "..." + plainKey.substring(plainKey.length() - 4);
        }
        return view;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiKeyMasked() { return apiKeyMasked; }
    public String getModelName() { return modelName; }
    public Boolean getIsActive() { return isActive; }
    public String getLastProbeStatus() { return lastProbeStatus; }
    public String getLastProbeMessage() { return lastProbeMessage; }
    public LocalDateTime getLastProbeAt() { return lastProbeAt; }
    public List<String> getCustomHeaderNames() { return customHeaderNames; }
}
