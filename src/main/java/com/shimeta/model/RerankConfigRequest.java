package com.shimeta.model;

import java.util.List;

public class RerankConfigRequest {
    private String id;
    private String name;
    private String baseUrl;
    private String apiKey;
    private String modelName;
    private Boolean isActive;
    /** 为 null 表示不修改；传入列表时整体替换，值为空的项沿用已保存的同名请求头的值 */
    private List<EmbeddingConfigRequest.HeaderEntry> customHeaders;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public List<EmbeddingConfigRequest.HeaderEntry> getCustomHeaders() { return customHeaders; }
    public void setCustomHeaders(List<EmbeddingConfigRequest.HeaderEntry> customHeaders) { this.customHeaders = customHeaders; }
}
