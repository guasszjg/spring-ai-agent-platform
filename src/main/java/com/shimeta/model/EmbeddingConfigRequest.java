package com.shimeta.model;

import java.util.List;

public class EmbeddingConfigRequest {
    private String id;
    /**
     * 自定义请求头。为 null 表示不修改；传入列表时整体替换，其中值为空的项沿用已保存的同名请求头的值
     * （编辑时前端看不到已保存的值，留空即保持不变）。
     */
    private List<HeaderEntry> customHeaders;
    private String name;
    private String provider;
    private String baseUrl;
    private String apiKey;
    private String modelName;
    private Integer dimension;
    private Boolean isActive;
    private Boolean enabled;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public Integer getDimension() { return dimension; }
    public void setDimension(Integer dimension) { this.dimension = dimension; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public List<HeaderEntry> getCustomHeaders() { return customHeaders; }
    public void setCustomHeaders(List<HeaderEntry> customHeaders) { this.customHeaders = customHeaders; }

    public static class HeaderEntry {
        private String name;
        private String value;

        public HeaderEntry() {
        }

        public HeaderEntry(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }
}
