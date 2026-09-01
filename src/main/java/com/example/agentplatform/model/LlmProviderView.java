package com.example.agentplatform.model;

import java.time.LocalDateTime;
import java.util.List;

public class LlmProviderView {
    private String id;
    private LlmProviderType vendor;
    private String name;
    private String baseUrl;
    private boolean configured;
    private String apiKeyMasked;
    private String defaultModel;
    private String models;
    private List<String> modelList;
    private boolean enabled;
    private boolean builtin;
    private Integer timeoutMs;
    private Integer maxRetries;
    private String remark;
    private String lastProbeStatus;
    private String lastProbeMessage;
    private LocalDateTime lastProbeAt;
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public LlmProviderType getVendor() {
        return vendor;
    }

    public void setVendor(LlmProviderType vendor) {
        this.vendor = vendor;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean isConfigured() {
        return configured;
    }

    public void setConfigured(boolean configured) {
        this.configured = configured;
    }

    public String getApiKeyMasked() {
        return apiKeyMasked;
    }

    public void setApiKeyMasked(String apiKeyMasked) {
        this.apiKeyMasked = apiKeyMasked;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public String getModels() {
        return models;
    }

    public void setModels(String models) {
        this.models = models;
    }

    public List<String> getModelList() {
        return modelList;
    }

    public void setModelList(List<String> modelList) {
        this.modelList = modelList;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isBuiltin() {
        return builtin;
    }

    public void setBuiltin(boolean builtin) {
        this.builtin = builtin;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getLastProbeStatus() {
        return lastProbeStatus;
    }

    public void setLastProbeStatus(String lastProbeStatus) {
        this.lastProbeStatus = lastProbeStatus;
    }

    public String getLastProbeMessage() {
        return lastProbeMessage;
    }

    public void setLastProbeMessage(String lastProbeMessage) {
        this.lastProbeMessage = lastProbeMessage;
    }

    public LocalDateTime getLastProbeAt() {
        return lastProbeAt;
    }

    public void setLastProbeAt(LocalDateTime lastProbeAt) {
        this.lastProbeAt = lastProbeAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
