package com.example.agentplatform.rag.dto;

public class KnowledgeEngineInfo {
    private boolean configured;
    private String baseUrl;
    private String host;
    /** 已配置且最近一次连通性探测成功，才允许新建 Dify 外挂知识库 */
    private boolean ready;
    /** 最近一次探测状态：SUCCESS / FAILED / UNTESTED / UNCONFIGURED */
    private String probeStatus;

    public boolean isConfigured() {
        return configured;
    }

    public void setConfigured(boolean configured) {
        this.configured = configured;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public String getProbeStatus() {
        return probeStatus;
    }

    public void setProbeStatus(String probeStatus) {
        this.probeStatus = probeStatus;
    }
}
