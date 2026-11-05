package com.shimeta.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.model.RerankConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RerankConfigServiceTest {

    private final RerankConfigService service = new RerankConfigService(null, null, new ObjectMapper(), null);

    @Test
    void sendsStandardRequestAndParsesResponse() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/rerank", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}],\"usage\":{\"total_tokens\":7}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            RerankConfig config = new RerankConfig();
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            config.setModelName("any-model");

            RerankConfigService.Result result = service.rerank(config, "问题", List.of("文档一", "文档二"));

            assertThat(result.hits()).extracting(RerankConfigService.Hit::index).containsExactly(1, 0);
            assertThat(result.totalTokens()).isEqualTo(7);
            assertThat(auth.get()).isNull();
            var sent = new ObjectMapper().readTree(received.get());
            assertThat(sent.path("model").asText()).isEqualTo("any-model");
            assertThat(sent.path("query").asText()).isEqualTo("问题");
            assertThat(sent.path("documents")).hasSize(2);
            assertThat(sent.path("top_n").asInt()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolvesEndpoint() {
        assertThat(RerankConfigService.resolveEndpoint("https://api.example.com/v1/")).isEqualTo("https://api.example.com/v1/rerank");
        assertThat(RerankConfigService.resolveEndpoint("https://api.example.com/v1/rerank")).isEqualTo("https://api.example.com/v1/rerank");
        assertThat(RerankConfigService.resolveEndpoint("https://api.example.com/v1/reranks")).isEqualTo("https://api.example.com/v1/reranks");
        assertThat(RerankConfigService.resolveEndpoint("https://api.example.com/x/textRerank?a=1")).isEqualTo("https://api.example.com/x/textRerank?a=1");
    }

    @Test
    void parsesCommonResponseShapesSortedByScore() {
        RerankConfigService.Result standard = service.parseResponse(
                "{\"results\":[{\"index\":0,\"relevance_score\":0.1},{\"index\":1,\"relevance_score\":0.8}],\"usage\":{\"total_tokens\":42}}", 2);
        assertThat(standard.hits()).extracting(RerankConfigService.Hit::index).containsExactly(1, 0);
        assertThat(standard.totalTokens()).isEqualTo(42);

        RerankConfigService.Result data = service.parseResponse("{\"data\":[{\"index\":0,\"score\":0.5}]}", 1);
        assertThat(data.hits().get(0).score()).isEqualTo(0.5);
        assertThat(data.totalTokens()).isZero();

        RerankConfigService.Result nested = service.parseResponse("{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.7}]}}", 1);
        assertThat(nested.hits()).hasSize(1);
    }

    @Test
    void ignoresIndexesOutOfRange() {
        RerankConfigService.Result result = service.parseResponse(
                "{\"results\":[{\"index\":9,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}]}", 1);
        assertThat(result.hits()).extracting(RerankConfigService.Hit::index).containsExactly(0);
    }

    @Test
    void reportsBusinessErrors() {
        assertThatThrownBy(() -> service.parseResponse("{\"code\":201,\"msg\":\"额度不足\"}", 1))
                .hasMessageContaining("额度不足");
        assertThatThrownBy(() -> service.parseResponse("not json", 1)).hasMessageContaining("不是 JSON");
        assertThatThrownBy(() -> service.parseResponse("", 1)).hasMessageContaining("为空");
    }
}
