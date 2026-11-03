package com.shimeta.service;

import com.shimeta.model.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 智能体思考模式：新建默认 OFF；AUTO 不传参数（模型默认），ON / OFF 显式开关 */
class AgentThinkingModeTest {

    @Test
    @DisplayName("新建智能体默认关闭思考")
    void defaultIsOff() {
        Agent agent = new Agent();
        assertThat(agent.getThinkingMode()).isEqualTo("OFF");
        assertThat(agent.resolveThinking()).isFalse();
        agent.setThinkingMode(null);
        assertThat(agent.resolveThinking()).isFalse();
    }

    @Test
    @DisplayName("AUTO 跟随模型：不向模型传思考参数")
    void autoFollowsModel() {
        Agent agent = new Agent();
        agent.setThinkingMode("AUTO");
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
    @DisplayName("规范化：空值取默认 OFF（手动 / 开放接口 / AI 助手创建不传时），非法取值拒绝")
    void normalize() {
        assertThat(Agent.normalizeThinkingMode(null)).isEqualTo("OFF");
        assertThat(Agent.normalizeThinkingMode(" ")).isEqualTo("OFF");
        assertThat(Agent.normalizeThinkingMode("auto")).isEqualTo("AUTO");
        assertThat(Agent.normalizeThinkingMode(" off ")).isEqualTo("OFF");
        assertThatThrownBy(() -> Agent.normalizeThinkingMode("maybe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUTO");
    }
}
