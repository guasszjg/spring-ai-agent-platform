package com.shimeta.tool;

import com.shimeta.config.OutboundUrlValidator;
import com.shimeta.service.ToolUsageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 工具调用统计：每次执行记一笔，按工具编码和智能体归档，识别失败 */
class ToolUsageRecordingTest {

    private final ToolUsageService usage = mock(ToolUsageService.class);
    private final AgentToolRegistry registry = new AgentToolRegistry("", mock(OutboundUrlValidator.class));

    @BeforeEach
    void setUp() {
        registry.setUsageService(usage);
        registry.setCurrentContextAgentId("agent-1");
    }

    @AfterEach
    void tearDown() {
        registry.clearCurrentContext();
    }

    @Test
    @DisplayName("成功的调用按工具编码与智能体记一笔；别名归到同一工具")
    void recordsSuccessfulCallsWithAlias() {
        registry.execute("time_get_current_time", "{}");
        registry.execute("get_current_time", "{}");

        verify(usage, org.mockito.Mockito.times(2)).record(eq("time_get_current_time"), eq("agent-1"), eq(true), anyLong());
    }

    @Test
    @DisplayName("没有 Key 等失败结果记为失败调用")
    void recordsFailures() {
        registry.execute("deepseek_web_search", "{\"query\":\"天气\"}");

        verify(usage).record(eq("deepseek_web_search"), eq("agent-1"), eq(false), anyLong());
    }

    @Test
    @DisplayName("未知工具不记录")
    void ignoresUnknownTools() {
        registry.execute("not_a_tool", "{}");

        verify(usage, never()).record(anyString(), anyString(), anyBoolean(), anyLong());
    }
}
