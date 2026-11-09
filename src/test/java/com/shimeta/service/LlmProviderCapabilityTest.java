package com.shimeta.service;

import com.shimeta.model.LlmProtocolType;
import com.shimeta.model.LlmProvider;
import com.shimeta.model.LlmProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmProviderCapabilityTest {

    private static LlmProvider provider(LlmProviderType vendor, LlmProtocolType protocol, Boolean supportsTools) {
        LlmProvider p = new LlmProvider();
        p.setVendor(vendor);
        p.setProtocol(protocol);
        p.setSupportsTools(supportsTools);
        return p;
    }

    @Test
    @DisplayName("没设置过工具调用能力（升级前的通道）：保持原来的行为，只有自定义 HTTP 不支持")
    void inferredDefaultsKeepOldBehavior() {
        assertThat(LlmGatewayService.supportsTools(provider(LlmProviderType.DEEPSEEK, LlmProtocolType.OPENAI, null))).isTrue();
        assertThat(LlmGatewayService.supportsTools(provider(LlmProviderType.CUSTOM, LlmProtocolType.OPENAI, null))).isTrue();
        assertThat(LlmGatewayService.supportsTools(provider(LlmProviderType.CUSTOM, LlmProtocolType.OLLAMA, null))).isTrue();
        assertThat(LlmGatewayService.supportsTools(provider(LlmProviderType.ZHIPU, LlmProtocolType.CUSTOM_HTTP, null))).isFalse();
    }

    @Test
    @DisplayName("管理员明确设置的值优先")
    void explicitValueWins() {
        assertThat(LlmGatewayService.supportsTools(provider(LlmProviderType.CUSTOM, LlmProtocolType.OLLAMA, true))).isTrue();
        assertThat(LlmGatewayService.supportsTools(provider(LlmProviderType.DEEPSEEK, LlmProtocolType.OPENAI, false))).isFalse();
    }

    @Test
    @DisplayName("上下文窗口：0 表示清空，过小的值抬到下限")
    void normalizeContextWindow() {
        assertThat(LlmGatewayService.normalizeContextWindow(0)).isNull();
        assertThat(LlmGatewayService.normalizeContextWindow(100)).isEqualTo(LlmGatewayService.MIN_CONTEXT_WINDOW);
        assertThat(LlmGatewayService.normalizeContextWindow(4096)).isEqualTo(4096);
    }
}
