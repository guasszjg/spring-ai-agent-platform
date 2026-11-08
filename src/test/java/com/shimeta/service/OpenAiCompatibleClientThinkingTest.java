package com.shimeta.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.config.OutboundUrlValidator;
import com.shimeta.model.ChatGeneration;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 智谱 GLM-5.3 系列始终思考，收到"关闭思考"会返回 400，主通道失败后会被悄悄降级到备用通道。
 * 这类模型关闭思考时不传思考参数；其他模型和"开启思考"不受影响。
 */
class OpenAiCompatibleClientThinkingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiCompatibleClient client = new OpenAiCompatibleClient(mock(OutboundUrlValidator.class));
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastBody.set(mapper.readTree(exchange.getRequestBody()));
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
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

    private JsonNode send(String model, Boolean thinking) {
        return send(model, thinking, com.shimeta.model.LlmProtocolType.OPENAI);
    }

    private JsonNode send(String model, Boolean thinking, com.shimeta.model.LlmProtocolType protocol) {
        ChatGeneration generation = new ChatGeneration();
        generation.setThinking(thinking);
        generation.setProviderProtocol(protocol);
        client.chat(baseUrl, "sk", model, List.of(Map.of("role", "user", "content", "hi")), generation, 10_000);
        return lastBody.get();
    }

    @Test
    void explicitOllamaChannelWorksOnAnArbitraryPort() {
        JsonNode body = send("qwen3:1.7b", false, com.shimeta.model.LlmProtocolType.OLLAMA);
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("none");
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("enable_thinking")).isFalse();
        body = send("deepseek-flash", false);
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.has("reasoning_effort")).isFalse();
    }

    @Test
    void ollamaUsesSupportedThinkingControl() {
        for (boolean enabled : List.of(false, true)) {
            ChatGeneration generation = new ChatGeneration();
            generation.setThinking(enabled);
            generation.setProviderProtocol(com.shimeta.model.LlmProtocolType.OLLAMA);
            var body = new java.util.LinkedHashMap<String, Object>();
            body.put("model", "qwen3:1.7b");
            body.put("enable_thinking", enabled);
            body.put("thinking", Map.of("type", enabled ? "enabled" : "disabled"));
            OpenAiCompatibleClient.applyOllamaThinking(body, generation);
            assertThat(body.get("reasoning_effort")).isEqualTo(enabled ? "low" : "none");
            assertThat(body).doesNotContainKeys("thinking", "enable_thinking");
        }
    }

    @Test
    void ollamaControlLeavesDefaultAndOtherProvidersAlone() {
        ChatGeneration generation = new ChatGeneration();
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("model", "qwen3:4b");
        generation.setProviderProtocol(com.shimeta.model.LlmProtocolType.OLLAMA);
        OpenAiCompatibleClient.applyOllamaThinking(body, generation);
        assertThat(body).doesNotContainKey("reasoning_effort");
        generation.setThinking(false);
        generation.setProviderProtocol(com.shimeta.model.LlmProtocolType.OPENAI);
        OpenAiCompatibleClient.applyOllamaThinking(body, generation);
        assertThat(body).doesNotContainKey("reasoning_effort");
    }

    @Test
    @DisplayName("GLM-5.3 系列关闭思考：不传思考参数（含中转通道的模型名）")
    void alwaysThinkingModelOmitsThinkingOff() {
        for (String model : List.of("glm-5.3", "glm-5.3-flash", "glm-5.3-flashx", "ZHIPU/GLM-5.3-Flash")) {
            JsonNode body = send(model, false);
            assertThat(body.has("thinking")).as(model).isFalse();
            assertThat(body.has("enable_thinking")).as(model).isFalse();
        }
    }

    @Test
    @DisplayName("GLM-5.3 系列开启思考：照常传参数")
    void alwaysThinkingModelKeepsThinkingOn() {
        JsonNode body = send("glm-5.3-flash", true);
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("enabled");
    }

    @Test
    @DisplayName("其他模型关闭思考：照常传 disabled")
    void otherModelsStillDisableThinking() {
        for (String model : List.of("glm-5.2", "glm-4.7-flash", "deepseek-flash")) {
            JsonNode body = send(model, false);
            assertThat(body.path("thinking").path("type").asText()).as(model).isEqualTo("disabled");
            assertThat(body.path("enable_thinking").asBoolean(true)).as(model).isFalse();
        }
    }
}
