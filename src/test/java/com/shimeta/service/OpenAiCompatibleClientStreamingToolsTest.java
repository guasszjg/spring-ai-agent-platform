package com.shimeta.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.config.OutboundUrlValidator;
import com.shimeta.tool.AgentToolRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 流式多轮工具调用：第一轮模型要求调用工具，执行后第二轮边生成边推送最终回答 */
class OpenAiCompatibleClientStreamingToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiCompatibleClient client = new OpenAiCompatibleClient(mock(OutboundUrlValidator.class));
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String baseUrl;

    private static String sse(String... events) {
        StringBuilder sb = new StringBuilder();
        for (String e : events) {
            sb.append("data: ").append(e).append("\n\n");
        }
        return sb.append("data: [DONE]\n\n").toString();
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode body = mapper.readTree(exchange.getRequestBody());
            requests.add(body);
            String reply = requests.size() == 1
                    // 第一轮：模型要求调用时间工具（参数分两段到达）
                    ? sse("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"time_get_current_time\",\"arguments\":\"{\\\"tz\\\":\"}}]}}]}",
                    "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Asia/Shanghai\\\"}\"}}]}}]}",
                    "{\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10}}")
                    // 第二轮：拿到工具结果后流式输出回答（带引用角标）
                    : sse("{\"choices\":[{\"delta\":{\"content\":\"现在是\"}}]}",
                    "{\"choices\":[{\"delta\":{\"content\":\"上午 10 点[1]\"}}]}",
                    "{\"choices\":[],\"usage\":{\"prompt_tokens\":150,\"completion_tokens\":20}}");
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("执行工具后继续流式生成，内容逐段推送，Token 为各轮之和，最终内容去掉角标")
    void streamsAcrossToolRounds() {
        AgentToolRegistry registry = mock(AgentToolRegistry.class);
        when(registry.execute("time_get_current_time", "{\"tz\":\"Asia/Shanghai\"}")).thenReturn("10:00");
        when(registry.formatToolCallSummary("time_get_current_time", "{\"tz\":\"Asia/Shanghai\"}")).thenReturn("获取当前时间");
        List<String> deltas = new ArrayList<>();

        OpenAiCompatibleClient.ChatResult result = client.chatWithToolsStreaming(baseUrl, "sk-test", "m", null, false,
                List.of(Map.of("role", "user", "content", "几点了")),
                List.of(Map.of("type", "function")), registry, null, 10_000, deltas::add, () -> false);

        assertThat(deltas).containsExactly("现在是", "上午 10 点[1]");
        assertThat(result.content()).isEqualTo("现在是上午 10 点");
        assertThat(result.promptTokens()).isEqualTo(250);
        assertThat(result.completionTokens()).isEqualTo(30);
        assertThat(result.toolCalled()).isEqualTo("获取当前时间");
        verify(registry).execute("time_get_current_time", "{\"tz\":\"Asia/Shanghai\"}");

        // 第二轮请求带上了工具调用与工具结果，且是流式请求
        JsonNode second = requests.get(1);
        assertThat(second.path("stream").asBoolean()).isTrue();
        JsonNode msgs = second.path("messages");
        assertThat(msgs.get(1).path("tool_calls").get(0).path("function").path("name").asText()).isEqualTo("time_get_current_time");
        assertThat(msgs.get(2).path("role").asText()).isEqualTo("tool");
        assertThat(msgs.get(2).path("content").asText()).isEqualTo("10:00");
    }

    @Test
    @DisplayName("调用方取消后不再执行工具和发起下一轮")
    void stopsWhenCancelled() {
        AgentToolRegistry registry = mock(AgentToolRegistry.class);

        client.chatWithToolsStreaming(baseUrl, "sk-test", "m", null, false,
                List.of(Map.of("role", "user", "content", "几点了")),
                List.of(Map.of("type", "function")), registry, null, 10_000, d -> { }, () -> true);

        assertThat(requests).hasSize(1);
        org.mockito.Mockito.verifyNoInteractions(registry);
    }
}
