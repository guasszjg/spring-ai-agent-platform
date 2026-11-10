package com.shimeta.tool;

import com.shimeta.config.OutboundUrlValidator;
import com.shimeta.mcp.McpToolInvoker;
import com.shimeta.mcp.McpToolSpec;
import com.shimeta.service.ToolUsageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MCP 工具接入工具注册表：只在勾选时发给模型，调用转发给 MCP 服务并计入用量 */
class McpToolRegistryTest {

    private final AgentToolRegistry registry = new AgentToolRegistry("", mock(OutboundUrlValidator.class));
    private final McpToolInvoker invoker = mock(McpToolInvoker.class);
    private final ToolUsageService usage = mock(ToolUsageService.class);
    private final McpToolSpec weather = new McpToolSpec("mcp_amap__maps_weather", "mcp-1", "高德地图", "maps_weather", "天气查询",
            "查询城市天气", Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))), false);

    @BeforeEach
    void setUp() {
        registry.setMcpInvoker(invoker);
        registry.setUsageService(usage);
        registry.setMcpTools(List.of(weather));
        registry.setCurrentContextAgentId("a1");
    }

    @AfterEach
    void tearDown() {
        registry.clearCurrentContext();
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(List<Map<String, Object>> tools) {
        return tools.stream().map(t -> (String) ((Map<String, Object>) t.get("function")).get("name")).toList();
    }

    @Test
    @DisplayName("智能体勾选了才发给模型；没勾选任何工具时内置工具照旧全带，但不带 MCP 工具")
    void onlyWhenSelected() {
        assertThat(names(registry.getToolDefinitions(List.of("mcp_amap__maps_weather")))).containsExactly("mcp_amap__maps_weather");
        assertThat(names(registry.getToolDefinitions(List.of("获取当前时间")))).doesNotContain("mcp_amap__maps_weather");
        assertThat(names(registry.getToolDefinitions(List.of()))).doesNotContain("mcp_amap__maps_weather").isNotEmpty();
        assertThat(names(registry.getToolDefinitions(null))).doesNotContain("mcp_amap__maps_weather");
    }

    @Test
    @DisplayName("调用转发给 MCP 服务，成功和失败都计入工具用量")
    void executeAndRecordUsage() {
        when(invoker.call(weather, "{\"city\":\"深圳\"}")).thenReturn("晴 25 度");
        assertThat(registry.execute("mcp_amap__maps_weather", "{\"city\":\"深圳\"}")).isEqualTo("晴 25 度");
        verify(usage).record(eq("mcp_amap__maps_weather"), eq("a1"), eq(true), anyLong());

        when(invoker.call(weather, "{}")).thenReturn(McpToolInvoker.FAILURE_PREFIX + "请求超时");
        registry.execute("mcp_amap__maps_weather", "{}");
        verify(usage).record(eq("mcp_amap__maps_weather"), eq("a1"), eq(false), anyLong());
    }

    @Test
    @DisplayName("调试窗口里的调用摘要显示服务名和原始工具名")
    void summary() {
        assertThat(registry.formatToolCallSummary("mcp_amap__maps_weather", "{\"city\":\"深圳\"}"))
                .isEqualTo("MCP 高德地图 · maps_weather (city=深圳)");
    }

    @Test
    @DisplayName("对话结束清理后不再能调用")
    void clearedContext() {
        registry.clearCurrentContext();
        assertThat(registry.execute("mcp_amap__maps_weather", "{}")).startsWith("未知工具");
    }
}
