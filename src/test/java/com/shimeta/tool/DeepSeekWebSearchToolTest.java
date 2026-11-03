package com.shimeta.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.config.OutboundUrlValidator;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** DeepSeek 联网搜索工具：请求参数、结果整理、Key 与错误提示 */
class DeepSeekWebSearchToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentToolRegistry registry = new AgentToolRegistry("", mock(OutboundUrlValidator.class));
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private HttpServer server;

    private static final String RESULTS = """
            {"results":[
              {"rerank_score":1.2,"title":"低分结果","url":"https://b.example","snippet":"短摘要B","summary":"","site_name":null,"date":null,"authority":1},
              {"rerank_score":2.6,"title":"深圳天气预报","url":"https://a.example/sz","snippet":"短摘要A","summary":"深圳明天多云，24 到 31 度。","site_name":"中国天气网","date":"2026-11-02T00:00:00+00:00","authority":4}
            ]}""";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/tools/search", exchange -> {
            lastBody.set(mapper.readTree(exchange.getRequestBody()));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = (status.get() == 200 ? RESULTS : "{\"error\":{\"message\":\"x\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        registry.deepSeekSearchUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/tools/search";
    }

    @AfterEach
    void stop() {
        server.stop(0);
        registry.clearCurrentContext();
    }

    @Test
    @DisplayName("只有启用了才出现在工具定义里，函数名为 deepseek_web_search")
    void definitionOnlyWhenEnabled() {
        assertThat(registry.getToolDefinitions(List.of("联网检索")))
                .noneMatch(t -> ((Map<?, ?>) t.get("function")).get("name").equals("deepseek_web_search"));
        assertThat(registry.getToolDefinitions(List.of("DeepSeek 联网搜索")))
                .anyMatch(t -> ((Map<?, ?>) t.get("function")).get("name").equals("deepseek_web_search"));
    }

    @Test
    @DisplayName("按平台配置条数请求，结果按相关度排序并整理为标题 / 来源 / 日期 / 长摘要 / 网址")
    void searchesAndFormatsResults() {
        registry.setCurrentContextDeepSeekKey("sk-ds-test");
        registry.setPlatformConfigs(Map.of("deepseek_web_search", Map.of("count", 2)));

        String out = registry.execute("deepseek_web_search", "{\"query\":\"深圳明天天气\"}");

        assertThat(lastAuth.get()).isEqualTo("Bearer sk-ds-test");
        assertThat(lastBody.get().path("query").asText()).isEqualTo("深圳明天天气");
        assertThat(lastBody.get().path("count").asInt()).isEqualTo(2);
        assertThat(out).contains("DeepSeek 检索到")
                .contains("1. 深圳天气预报（来源: 中国天气网） [2026-11-02]")
                .contains("摘要内容: 深圳明天多云，24 到 31 度。")
                .contains("网页参考: https://a.example/sz")
                .contains("2. 低分结果")
                .contains("摘要内容: 短摘要B")
                .doesNotContain("来源: null");
        assertThat(out.indexOf("深圳天气预报")).isLessThan(out.indexOf("低分结果"));
    }

    @Test
    @DisplayName("超长关键词截到 128 字；没有 Key 时不发请求并给出配置指引")
    void truncatesQueryAndHandlesMissingKey() {
        String noKey = registry.execute("deepseek_web_search", "{\"query\":\"天气\"}");
        assertThat(noKey).contains("未找到可用的 DeepSeek Key");
        assertThat(lastBody.get()).isNull();

        registry.setCurrentContextDeepSeekKey("sk-ds-test");
        registry.execute("deepseek_web_search", "{\"query\":\"" + "广".repeat(200) + "\"}");
        assertThat(lastBody.get().path("query").asText()).hasSize(128);
    }

    @Test
    @DisplayName("余额不足、Key 无效时返回可读的提示；连通性测试同样识别")
    void explainsErrors() {
        registry.setCurrentContextDeepSeekKey("sk-ds-test");
        status.set(402);
        assertThat(registry.execute("deepseek_web_search", "{\"query\":\"天气\"}")).contains("余额不足");

        status.set(401);
        Map<String, Object> test = registry.testDeepSeekSearch("sk-bad");
        assertThat(test.get("success")).isEqualTo(false);
        assertThat((String) test.get("message")).contains("Key 无效");

        status.set(200);
        assertThat(registry.testDeepSeekSearch("sk-ok").get("success")).isEqualTo(true);
        assertThat(registry.testDeepSeekSearch(null).get("success")).isEqualTo(false);
    }
}
