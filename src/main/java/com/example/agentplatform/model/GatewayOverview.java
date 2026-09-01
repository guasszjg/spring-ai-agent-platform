package com.example.agentplatform.model;

import java.util.List;

public class GatewayOverview {
    private List<LlmProviderView> providers;
    private GatewayPolicy policy;
    private int configuredCount;
    private int enabledCount;
    private int readyCount;

    public List<LlmProviderView> getProviders() {
        return providers;
    }

    public void setProviders(List<LlmProviderView> providers) {
        this.providers = providers;
    }

    public GatewayPolicy getPolicy() {
        return policy;
    }

    public void setPolicy(GatewayPolicy policy) {
        this.policy = policy;
    }

    public int getConfiguredCount() {
        return configuredCount;
    }

    public void setConfiguredCount(int configuredCount) {
        this.configuredCount = configuredCount;
    }

    public int getEnabledCount() {
        return enabledCount;
    }

    public void setEnabledCount(int enabledCount) {
        this.enabledCount = enabledCount;
    }

    public int getReadyCount() {
        return readyCount;
    }

    public void setReadyCount(int readyCount) {
        this.readyCount = readyCount;
    }
}
