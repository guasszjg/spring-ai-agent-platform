package com.shimeta.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 调试页选择模型通道用的数据：所有已启用的大模型通道（只有 selectable 的可以选），以及平台默认路由。
 * 不含地址、Key 等配置，开发者也能查看。
 */
public record ChatRouteOptions(List<Channel> channels, PlatformRoute platform) {

    /**
     * @param selectable        已启用、配置了 Key、最近一次连通测试通过
     * @param unavailableReason 不能选时的原因
     */
    public record Channel(
            String id,
            String name,
            LlmProviderType vendor,
            String defaultModel,
            List<String> models,
            boolean supportsTools,
            Integer contextWindow,
            boolean selectable,
            String unavailableReason,
            String lastProbeStatus,
            LocalDateTime lastProbeAt
    ) {
    }

    /**
     * 平台默认路由，智能体选「跟随平台默认」时使用。
     *
     * @param fallbackChannelId 平台开启自动降级且配置了可用备用通道时才有值
     */
    public record PlatformRoute(
            String channelId,
            String channelName,
            String model,
            String fallbackChannelId,
            String fallbackChannelName
    ) {
    }

    /** 调试页「测试」按钮的结果：用当前配置真实调用一次模型，不改动通道的测试状态 */
    public record TestResult(boolean success, String message, long latencyMs, String model, String reply) {
    }
}
