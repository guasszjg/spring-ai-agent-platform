package com.example.agentplatform.model;

public class IdentityBindRequest {
    private String provider;
    private String mode;
    private String externalId;
    private String displayName;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
}
