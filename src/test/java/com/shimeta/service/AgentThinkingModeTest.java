package com.shimeta.service;

import com.shimeta.model.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 智能体思考模式：AUTO 不传参数（模型默认），ON / OFF 显式开关 */
class AgentThinkingModeTest {

    @Test
    @DisplayName("默认跟随模型：不向模型传思考参数")
    void defaultFollowsModel() {
        Agent agent = new Agent();
        assertThat(agent.getThinkingMode()).isEqualTo("AUTO");
        assertThat(agent.resolveThinking()).isNull();
        agent.setThinkingMode(null);
        assertThat(agent.resolveThinking()).isNull();
    }

    @Test
    @DisplayName("ON / OFF 分别显式开启、关闭思考，大小写不敏感")
    void onAndOff() {
        Agent agent = new Agent();
        agent.setThinkingMode("off");
        assertThat(agent.resolveThinking()).isFalse();
        agent.setThinkingMode("ON");
        assertThat(agent.resolveThinking()).isTrue();
    }

    @Test
    @DisplayName("规范化：空值视为 AUTO，非法取值拒绝")
    void normalize() {
        assertThat(Agent.normalizeThinkingMode(null)).isEqualTo("AUTO");
        assertThat(Agent.normalizeThinkingMode(" off ")).isEqualTo("OFF");
        assertThatThrownBy(() -> Agent.normalizeThinkingMode("maybe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUTO");
    }
}
