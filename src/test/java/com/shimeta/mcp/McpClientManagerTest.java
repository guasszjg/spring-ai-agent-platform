package com.shimeta.mcp;

import com.shimeta.config.OutboundUrlValidator;
import com.shimeta.config.SecretCrypto;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class McpClientManagerTest {

    private final McpClientManager manager = new McpClientManager(mock(OutboundUrlValidator.class),
            new SecretCrypto("test-secret-for-mcp-unit-tests"), 500);

    @Test
    @DisplayName("工具结果：文本拼接，非文本内容留占位，报错的加失败前缀")
    void resultToText() {
        McpSchema.CallToolResult ok = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("晴"), new McpSchema.TextContent("25 度")), false, null, null);
        assertThat(manager.toText(ok)).isEqualTo("晴\n25 度");

        McpSchema.CallToolResult error = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("city not found")), true, null, null);
        assertThat(manager.toText(error)).isEqualTo(McpToolInvoker.FAILURE_PREFIX + "city not found");

        McpSchema.CallToolResult structured = new McpSchema.CallToolResult(List.of(), false, Map.of("temp", 25), null);
        assertThat(manager.toText(structured)).isEqualTo("{\"temp\":25}");

        assertThat(manager.toText(new McpSchema.CallToolResult(List.of(), false, null, null))).contains("没有返回内容");
    }

    @Test
    @DisplayName("结果过长：截断并告诉模型")
    void longResultIsTruncated() {
        McpSchema.CallToolResult big = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("数".repeat(2000))), false, null, null);
        String text = manager.toText(big);
        assertThat(text).hasSize(500 + McpClientManager.TRUNCATED_NOTE.length()).endsWith("已截断]");
    }

    @Test
    @DisplayName("自定义请求头：只收字符串值，Host 等由客户端管理的头忽略，不是对象时报错")
    void parseHeaders() {
        assertThat(manager.parseHeaders("{\"X-Api-Key\":\"k\",\"Host\":\"evil\",\"nested\":{\"a\":1}}"))
                .containsExactly(Map.entry("X-Api-Key", "k"));
        assertThatThrownBy(() -> manager.parseHeaders("[1,2]")).hasMessageContaining("JSON 对象");
        assertThatThrownBy(() -> manager.parseHeaders("not json")).hasMessageContaining("JSON");
    }

    @Test
    @DisplayName("超时识别和错误说明")
    void describeErrors() {
        RuntimeException timeout = new RuntimeException(new java.util.concurrent.TimeoutException("x"));
        assertThat(McpClientManager.isTimeout(timeout)).isTrue();
        assertThat(McpClientManager.describeError(timeout)).isEqualTo("请求超时");
        assertThat(McpClientManager.describeError(new RuntimeException(new java.net.ConnectException()))).isEqualTo("无法连接到服务地址");
        assertThat(McpClientManager.describeError(new IllegalStateException("HTTP 401"))).isEqualTo("HTTP 401");
    }
}
