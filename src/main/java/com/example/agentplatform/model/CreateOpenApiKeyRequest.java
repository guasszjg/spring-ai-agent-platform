package com.example.agentplatform.model;

import java.util.ArrayList;
import java.util.List;

public class CreateOpenApiKeyRequest {
    private String name;
    private String ownerId;
    private List<String> scopes = new ArrayList<>();
    private List<String> agentScope = new ArrayList<>();
    private List<String> ipAllowlist = new ArrayList<>();
    private Integer rateLimitRpm;
    private Long dailyTokenQuota;
    private String expiresAt;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public List<String> getScopes() { return scopes != null ? scopes : new ArrayList<>(); }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }
    public List<String> getAgentScope() { return agentScope != null ? agentScope : new ArrayList<>(); }
    public void setAgentScope(List<String> agentScope) { this.agentScope = agentScope; }
    public List<String> getIpAllowlist() { return ipAllowlist != null ? ipAllowlist : new ArrayList<>(); }
    public void setIpAllowlist(List<String> ipAllowlist) { this.ipAllowlist = ipAllowlist; }
    public Integer getRateLimitRpm() { return rateLimitRpm; }
    public void setRateLimitRpm(Integer rateLimitRpm) { this.rateLimitRpm = rateLimitRpm; }
    public Long getDailyTokenQuota() { return dailyTokenQuota; }
    public void setDailyTokenQuota(Long dailyTokenQuota) { this.dailyTokenQuota = dailyTokenQuota; }
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }
}
