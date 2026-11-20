package com.shimeta.model;

/**
 * 一次智能体对话要用的模型通道。字段为空表示跟随平台默认：
 * primaryProviderId 为空时按平台默认路由选主通道；fallbackProviderId 为空时按平台的降级设置选备用通道。
 * modelName 是主通道上的模型，不在该通道模型列表里时用通道默认模型。
 *
 * <p>调试页在请求里带上它表示「本窗口的草稿选择」，只对这一次请求生效；不带时用智能体已发布的设置。
 */
public class AgentRouteChoice {

    private String primaryProviderId;
    private String fallbackProviderId;
    private String modelName;

    public AgentRouteChoice() {
    }

    public AgentRouteChoice(String primaryProviderId, String fallbackProviderId, String modelName) {
        this.primaryProviderId = primaryProviderId;
        this.fallbackProviderId = fallbackProviderId;
        this.modelName = modelName;
    }

    public static AgentRouteChoice of(Agent agent) {
        return new AgentRouteChoice(agent.getPrimaryProviderId(), agent.getFallbackProviderId(), agent.getModelName());
    }

    /** 选了自己的主通道或备用通道；都没选时整条路由和以前一样跟随平台 */
    public boolean isCustom() {
        return hasText(primaryProviderId) || hasText(fallbackProviderId);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public String getPrimaryProviderId() {
        return primaryProviderId;
    }

    public void setPrimaryProviderId(String primaryProviderId) {
        this.primaryProviderId = primaryProviderId;
    }

    public String getFallbackProviderId() {
        return fallbackProviderId;
    }

    public void setFallbackProviderId(String fallbackProviderId) {
        this.fallbackProviderId = fallbackProviderId;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }
}
