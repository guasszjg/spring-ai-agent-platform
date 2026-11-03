package com.shimeta.model;

import java.util.Map;

public class PlatformToolUpdateRequest {
    private String name;
    private String title;
    private String help;
    private String description;
    private Boolean enabled;
    private Map<String, Object> config;
    private String apiKey;
    /** 单价（元/次），可选 */
    private java.math.BigDecimal unitPrice;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getHelp() { return help; }
    public void setHelp(String help) { this.help = help; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
    public java.math.BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(java.math.BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
}
