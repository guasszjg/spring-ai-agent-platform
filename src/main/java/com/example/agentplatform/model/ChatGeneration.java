package com.example.agentplatform.model;

public class ChatGeneration {
    private Double temperature;
    private Integer maxTokens;
    private Double topP;
    private Integer n;
    private Double frequencyPenalty;
    private String responseFormat;
    private Boolean webSearch;
    private Boolean thinking;
    private String extraHeaders;

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getN() {
        return n;
    }

    public void setN(Integer n) {
        this.n = n;
    }

    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public void setFrequencyPenalty(Double frequencyPenalty) {
        this.frequencyPenalty = frequencyPenalty;
    }

    public String getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(String responseFormat) {
        this.responseFormat = responseFormat;
    }

    public Boolean getWebSearch() {
        return webSearch;
    }

    public void setWebSearch(Boolean webSearch) {
        this.webSearch = webSearch;
    }

    public Boolean getThinking() {
        return thinking;
    }

    public void setThinking(Boolean thinking) {
        this.thinking = thinking;
    }

    public String getExtraHeaders() {
        return extraHeaders;
    }

    public void setExtraHeaders(String extraHeaders) {
        this.extraHeaders = extraHeaders;
    }
}
