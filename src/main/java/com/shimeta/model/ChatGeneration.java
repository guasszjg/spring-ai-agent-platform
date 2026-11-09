package com.shimeta.model;

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
    // Set by the selected server-side channel, never trusted from a chat request.
    private LlmProtocolType providerProtocol = LlmProtocolType.OPENAI;

    /** 浅拷贝：按通道调整参数（比如 max_tokens）时不改动调用方传进来的对象 */
    public ChatGeneration copy() {
        ChatGeneration copy = new ChatGeneration();
        copy.temperature = temperature;
        copy.maxTokens = maxTokens;
        copy.topP = topP;
        copy.n = n;
        copy.frequencyPenalty = frequencyPenalty;
        copy.responseFormat = responseFormat;
        copy.webSearch = webSearch;
        copy.thinking = thinking;
        copy.extraHeaders = extraHeaders;
        copy.providerProtocol = providerProtocol;
        return copy;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public LlmProtocolType getProviderProtocol() {
        return providerProtocol;
    }

    public void setProviderProtocol(LlmProtocolType providerProtocol) {
        this.providerProtocol = providerProtocol;
    }

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
