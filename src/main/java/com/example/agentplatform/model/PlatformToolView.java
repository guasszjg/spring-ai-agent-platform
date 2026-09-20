package com.example.agentplatform.model;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public class PlatformToolView {
    private String id;
    private String code;
    private String name;
    private String title;
    private String prefix;
    private String category;
    private String icon;
    private String iconClass;
    private String customIcon;
    private String help;
    private String description;
    private Map<String, Object> config = new LinkedHashMap<>();
    private Boolean enabled;
    private Boolean builtin;
    private Integer sortOrder;
    private boolean hasApiKey;
    private String apiKeyMasked;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getIconClass() { return iconClass; }
    public void setIconClass(String iconClass) { this.iconClass = iconClass; }
    public String getCustomIcon() { return customIcon; }
    public void setCustomIcon(String customIcon) { this.customIcon = customIcon; }
    public String getHelp() { return help; }
    public void setHelp(String help) { this.help = help; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config != null ? config : new LinkedHashMap<>(); }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getBuiltin() { return builtin; }
    public void setBuiltin(Boolean builtin) { this.builtin = builtin; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public boolean isHasApiKey() { return hasApiKey; }
    public void setHasApiKey(boolean hasApiKey) { this.hasApiKey = hasApiKey; }
    public String getApiKeyMasked() { return apiKeyMasked; }
    public void setApiKeyMasked(String apiKeyMasked) { this.apiKeyMasked = apiKeyMasked; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
