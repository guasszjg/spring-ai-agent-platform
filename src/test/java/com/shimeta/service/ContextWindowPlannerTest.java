package com.shimeta.service;

import com.shimeta.model.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextWindowPlannerTest {

    @Test
    @DisplayName("没配置上下文窗口：不做任何限制")
    void noWindowMeansNoPlan() {
        assertThat(ContextWindowPlanner.plan(null, 1024)).isNull();
        assertThat(ContextWindowPlanner.plan(0, null)).isNull();
    }

    @Test
    @DisplayName("没指定 max_tokens：输出预留窗口的四分之一，输入留一成余量")
    void defaultOutputReserve() {
        ContextWindowPlanner.Plan plan = ContextWindowPlanner.plan(4096, null);
        assertThat(plan.outputTokens()).isEqualTo(1024);
        assertThat(plan.inputBudget()).isEqualTo((int) (3072 * 0.9));
    }

    @Test
    @DisplayName("大窗口的默认输出预留最多 4096；指定的 max_tokens 不超过窗口四分之一")
    void outputReserveIsCapped() {
        assertThat(ContextWindowPlanner.plan(128_000, null).outputTokens()).isEqualTo(4096);
        assertThat(ContextWindowPlanner.plan(4096, 512).outputTokens()).isEqualTo(512);
        assertThat(ContextWindowPlanner.plan(4096, 2048).outputTokens()).isEqualTo(1024);
        assertThat(ContextWindowPlanner.plan(128_000, 8192).outputTokens()).isEqualTo(8192);
    }

    @Test
    @DisplayName("历史放不下时从最早的消息丢，且不以助手消息开头")
    void fitHistoryDropsOldestTurns() {
        String longText = "数".repeat(500);
        List<ChatMessage> history = List.of(
                new ChatMessage("user", longText),
                new ChatMessage("assistant", longText),
                new ChatMessage("user", "第二问"),
                new ChatMessage("assistant", "第二答"));

        assertThat(ContextWindowPlanner.fitHistory(history, 10_000)).isSameAs(history);

        List<ChatMessage> fitted = ContextWindowPlanner.fitHistory(history, 700);
        assertThat(fitted).extracting(ChatMessage::getContent).containsExactly("第二问", "第二答");

        assertThat(ContextWindowPlanner.fitHistory(history, 0)).isEmpty();
    }
}
