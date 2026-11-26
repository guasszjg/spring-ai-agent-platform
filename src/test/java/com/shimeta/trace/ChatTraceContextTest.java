package com.shimeta.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTraceContextTest {

    @AfterEach
    void tearDown() {
        ChatTraceContext.clear();
    }

    @Test
    void collectsNestedStepsEmitsLiveEventsAndRecordsFirstToken() {
        List<ChatTraceStep> liveEvents = new ArrayList<>();
        ChatTraceContext ctx = ChatTraceContext.begin("agent-1", "客服助手", liveEvents::add);

        String kbId = ctx.startStep("KB_RETRIEVE", "知识库检索", "检索中", Map.of("query", "会议预约"));
        ctx.pushParent(kbId);
        String embId = ctx.startStep("EMBEDDING", "向量模型编码", "qwen3-embedding:0.6b", null);
        ctx.finishStep(embId, "qwen3-embedding:0.6b · 1024维", Map.of("dimension", 1024));
        ctx.popParent(kbId);
        ctx.finishStep(kbId, "命中 2 条片段", Map.of("hitCount", 2));

        String llmId = ctx.startStep("LLM", "大模型推理", "deepseek-chat", null);
        ctx.recordFirstToken();
        ctx.finishStep(llmId, "deepseek-chat · 输入 120 / 输出 80 tok", Map.of("promptTokens", 120, "completionTokens", 80));

        ChatTrace snapshot = ctx.snapshotOk("deepseek-chat", "DeepSeek 官方", null, null, 120, 30, 80);
        assertEquals("ok", snapshot.getStatus());
        assertEquals("agent-1", snapshot.getAgentId());
        assertEquals("deepseek-chat", snapshot.getModel());
        assertEquals(200, snapshot.getTotalTokens());
        assertNotNull(snapshot.getFirstTokenMs());
        assertEquals(3, snapshot.getSteps().size());
        assertNull(snapshot.getSteps().get(0).getParentId());
        assertEquals(kbId, snapshot.getSteps().get(1).getParentId());
        assertEquals("ok", snapshot.getSteps().get(1).getStatus());
        // 3 个节点各触发 start + finish = 6 次实时回调
        assertEquals(6, liveEvents.size());
    }

    @Test
    void snapshotErrorMarksHangingRunningStepsAsError() {
        ChatTraceContext ctx = ChatTraceContext.begin("agent-2", "测试助手", null);
        String embId = ctx.startStep("EMBEDDING", "向量模型编码", "qwen3-embedding:0.6b", null);
        assertNotNull(embId);

        ChatTrace err = ctx.snapshotError("连接向量模型超时 (20s)");
        assertEquals("error", err.getStatus());
        assertEquals("连接向量模型超时 (20s)", err.getError());
        assertEquals(1, err.getSteps().size());
        assertEquals("error", err.getSteps().get(0).getStatus());
        assertTrue(err.getSteps().get(0).getError().contains("超时"));
    }
}
