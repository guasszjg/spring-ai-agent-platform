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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具调用轮次上限（平台设置，默认 5 轮）：模型一直要求调用工具时，用完上限后不再提供工具、提示模型根据已查到的资料作答；
 * 不再把"工具调用轮次达到上限"这类内部状态，或模型写成文本的工具调用标记，当成回答返回给用户。
 */
class OpenAiCompatibleClientToolLimitTest {

    /** DeepSeek 在没有工具可用时把工具调用写成文本的样子 */
    private static final String DSML_MARKUP = "<｜｜DSML｜｜ calls> <｜｜DSML｜｜ invoke name=\"bocha_web_search\">";

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiCompatibleClient client = new OpenAiCompatibleClient(mock(OutboundUrlValidator.class));
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    /** 为 true 时模型在没有工具可用时仍输出工具调用标记 */
    private final AtomicBoolean stubborn = new AtomicBoolean(false);
    private HttpServer server;
    private String baseUrl;
    private AgentToolRegistry registry;

    private String completion(String content, boolean toolCall) throws Exception {
        Map<String, Object> message = toolCall
                ? Map.of("tool_calls", List.of(Map.of("id", "c" + requests.size(), "type", "function",
                "function", Map.of("name", "bocha_web_search", "arguments", "{\"query\":\"q" + requests.size() + "\"}"))))
                : Map.of("content", content);
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", message)),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5)));
    }

    private String sse(List<String> contents, boolean toolCall) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (toolCall) {
            Map<String, Object> delta = Map.of("tool_calls", List.of(Map.of("index", 0, "id", "c" + requests.size(),
                    "function", Map.of("name", "bocha_web_search", "arguments", "{\"query\":\"q" + requests.size() + "\"}"))));
            sb.append("data: ").append(mapper.writeValueAsString(Map.of("choices", List.of(Map.of("delta", delta))))).append("\n\n");
        } else {
            for (String c : contents) {
                sb.append("data: ").append(mapper.writeValueAsString(Map.of("choices", List.of(Map.of("delta", Map.of("content", c))))))
                        .append("\n\n");
            }
        }
        return sb.append("data: [DONE]\n\n").toString();
    }

    @BeforeEach
    void setUp() throws Exception {
        registry = mock(AgentToolRegistry.class);
        when(registry.execute(anyString(), anyString())).thenReturn("搜索结果");
        when(registry.formatToolCallSummary(anyString(), anyString())).thenAnswer(inv -> "search(" + inv.getArgument(1) + ")");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String reply;
            try {
                JsonNode body = mapper.readTree(exchange.getRequestBody());
                requests.add(body);
                boolean noTools = !body.has("tools");
                boolean stream = body.path("stream").asBoolean(false);
                List<String> answer = noTools && stubborn.get() ? List.of(DSML_MARKUP) : List.of("根据检索结果，", "答案如下。");
                reply = stream ? sse(answer, !noTools) : completion(String.join("", answer), !noTools);
            } catch (Exception e) {
                reply = "{}";
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static List<Map<String, Object>> question() {
        return List.of(Map.of("role", "user", "content", "近一周 AI 大模型新闻"));
    }

    private static List<Map<String, Object>> tools() {
        return List.of(Map.of("type", "function", "function", Map.of("name", "bocha_web_search")));
    }

    private void assertFinalRoundHasNoToolsAndNudge(int rounds) {
        assertThat(requests).hasSize(rounds + 1);
        assertThat(requests.subList(0, rounds)).allMatch(r -> r.has("tools"));
        JsonNode last = requests.get(rounds);
        assertThat(last.has("tools")).isFalse();
        JsonNode msgs = last.path("messages");
        assertThat(msgs.get(msgs.size() - 1).path("content").asText()).isEqualTo(OpenAiCompatibleClient.FINAL_ROUND_NUDGE);
    }

    @Test
    @DisplayName("阻塞：默认 5 轮工具后不再提供工具并提示作答，返回模型的回答，工具摘要记录全部轮次")
    void blockingAnswersAfterToolRounds() {
        OpenAiCompatibleClient.ChatResult result = client.chatWithTools(baseUrl, "sk", "m", question(), tools(), registry, null, 10_000);

        assertThat(result.content()).isEqualTo("根据检索结果，答案如下。");
        assertFinalRoundHasNoToolsAndNudge(OpenAiCompatibleClient.DEFAULT_MAX_TOOL_ROUNDS);
        verify(registry, times(5)).execute(anyString(), anyString());
        assertThat(result.toolCalled().split(" \\| ")).hasSize(5);
    }

    @Test
    @DisplayName("轮数上限可配置：设为 2 时第 3 次请求就不再提供工具")
    void roundLimitIsConfigurable() {
        OpenAiCompatibleClient.ChatResult result = client.chatWithTools(baseUrl, "sk", "m", null, false, question(), tools(),
                registry, null, 10_000, 2);

        assertThat(result.content()).isEqualTo("根据检索结果，答案如下。");
        assertFinalRoundHasNoToolsAndNudge(2);
        verify(registry, times(2)).execute(anyString(), anyString());
    }

    @Test
    @DisplayName("流式：最后一轮同样不提供工具，回答正常推送")
    void streamingAnswersAfterToolRounds() {
        List<String> deltas = new ArrayList<>();

        OpenAiCompatibleClient.ChatResult result = client.chatWithToolsStreaming(baseUrl, "sk", "m", null, false,
                question(), tools(), registry, null, 10_000, deltas::add, () -> false, 3);

        assertThat(result.content()).isEqualTo("根据检索结果，答案如下。");
        assertThat(String.join("", deltas)).isEqualTo("根据检索结果，答案如下。");
        assertFinalRoundHasNoToolsAndNudge(3);
        assertThat(result.toolCalled().split(" \\| ")).hasSize(3);
    }

    @Test
    @DisplayName("模型把工具调用写成文本标记（DSML）时不当作回答，给出友好兜底回复，标记不会推送给用户")
    void fallbackWhenModelWritesToolMarkup() {
        stubborn.set(true);
        List<String> deltas = new ArrayList<>();

        OpenAiCompatibleClient.ChatResult blocking = client.chatWithTools(baseUrl, "sk", "m", question(), tools(), registry, null, 10_000);
        OpenAiCompatibleClient.ChatResult streaming = client.chatWithToolsStreaming(baseUrl, "sk", "m", null, false,
                question(), tools(), registry, null, 10_000, deltas::add, () -> false);

        assertThat(blocking.content()).isEqualTo(OpenAiCompatibleClient.TOOL_LIMIT_FALLBACK).doesNotContain("上限");
        assertThat(streaming.content()).isEqualTo(OpenAiCompatibleClient.TOOL_LIMIT_FALLBACK);
        assertThat(deltas).containsExactly(OpenAiCompatibleClient.TOOL_LIMIT_FALLBACK);
        verify(registry, times(10)).execute(anyString(), anyString());
    }
}
