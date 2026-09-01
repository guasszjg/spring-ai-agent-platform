package com.example.agentplatform.model;

public class GatewayModelOption {
    private String model;
    private String label;
    private String providerId;
    private String providerName;
    private boolean ready;

    public GatewayModelOption() {
    }

    public GatewayModelOption(String model, String label, String providerId, String providerName, boolean ready) {
        this.model = model;
        this.label = label;
        this.providerId = providerId;
        this.providerName = providerName;
        this.ready = ready;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }
}
