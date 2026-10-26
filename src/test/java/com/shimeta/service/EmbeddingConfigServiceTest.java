package com.shimeta.service;

import com.shimeta.config.SecretCrypto;
import com.shimeta.model.EmbeddingConfig;
import com.shimeta.model.EmbeddingConfigRequest;
import com.shimeta.model.EmbeddingConfigRequest.HeaderEntry;
import com.shimeta.model.EmbeddingConfigView;
import com.shimeta.repository.EmbeddingConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmbeddingConfigServiceTest {

    @Mock EmbeddingConfigRepository repository;

    private final SecretCrypto crypto = new SecretCrypto("unit-test-crypto-key");
    private final ObjectMapper mapper = new ObjectMapper();
    private EmbeddingConfigService service;
    private HttpServer server;
    private final AtomicReference<Map<String, String>> receivedHeaders = new AtomicReference<>();
    private final AtomicReference<JsonNode> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private volatile String responseBody;

    @BeforeEach
    void setUp() throws Exception {
        service = new EmbeddingConfigService(repository, crypto, mapper);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 模拟中转服务：记录请求头、路径与请求体，返回预设内容（业务错误同样是 HTTP 200）
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedHeaders.set(Map.of(
                    "app_id", String.valueOf(exchange.getRequestHeaders().getFirst("app_id")),
                    "device_mac", String.valueOf(exchange.getRequestHeaders().getFirst("device_mac")),
                    "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"))));
            receivedBody.set(mapper.readTree(exchange.getRequestBody()));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void endpointIsAppendedOnlyForBaseUrls() {
        assertEquals("https://api.openai.com/v1/embeddings", EmbeddingConfigService.resolveEndpoint(null));
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings",
                EmbeddingConfigService.resolveEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1/"));
        assertEquals("https://x.com/v1/embeddings", EmbeddingConfigService.resolveEndpoint("https://x.com/v1/embeddings"));
        // 中转服务的完整接口地址：最后一段以 Embeddings 结尾（不区分大小写）时原样使用
        assertEquals("http://82.157.197.25:9540/api/modelConfig/textEmbeddings",
                EmbeddingConfigService.resolveEndpoint("http://82.157.197.25:9540/api/modelConfig/textEmbeddings"));
    }

    @Test
    void sendsCustomHeadersAndArrayInputToFullEndpoint() {
        responseBody = "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3],\"object\":\"embedding\"}],\"object\":\"list\"}";

        List<float[]> vectors = service.requestEmbeddings(base() + "/api/modelConfig/textEmbeddings", null,
                Map.of("app_id", "APP1", "device_mac", "8CFCA02881BE"), "kinfra-text-embedding-0.6b",
                List.of("你好"), Duration.ofSeconds(3), Duration.ofSeconds(5));

        assertEquals(1, vectors.size());
        assertEquals(3, vectors.get(0).length);
        assertEquals("/api/modelConfig/textEmbeddings", receivedPath.get());
        assertEquals("APP1", receivedHeaders.get().get("app_id"));
        assertEquals("8CFCA02881BE", receivedHeaders.get().get("device_mac"));
        assertEquals("null", receivedHeaders.get().get("authorization"));
        assertTrue(receivedBody.get().path("input").isArray());
        assertEquals("你好", receivedBody.get().path("input").get(0).asText());
        assertFalse(receivedBody.get().has("encoding_format"));
    }

    @Test
    void http200BusinessErrorIsReportedAsFailure() {
        responseBody = "{\"msg\":\"deviceMac不能为空\",\"code\":201}";

        var e = assertThrows(IllegalStateException.class, () -> service.requestEmbeddings(base() + "/v1", "sk-test",
                Map.of(), "m", List.of("x"), Duration.ofSeconds(3), Duration.ofSeconds(5)));
        assertTrue(e.getMessage().contains("deviceMac不能为空"));
        assertTrue(e.getMessage().contains("code=201"));
        assertEquals("/v1/embeddings", receivedPath.get());

        EmbeddingConfigRequest req = new EmbeddingConfigRequest();
        req.setBaseUrl(base() + "/v1");
        req.setModelName("m");
        Map<String, Object> result = service.probeDraft(req);
        assertEquals(false, result.get("success"));
        assertTrue(String.valueOf(result.get("message")).contains("deviceMac不能为空"));
    }

    @Test
    void headersAreEncryptedAndOnlyNamesAreExposed() {
        when(repository.count()).thenReturn(1L);
        EmbeddingConfigRequest req = new EmbeddingConfigRequest();
        req.setName("中转向量");
        req.setModelName("kinfra-text-embedding-0.6b");
        req.setBaseUrl("http://relay/api/modelConfig/textEmbeddings");
        req.setCustomHeaders(List.of(new HeaderEntry("app_id", "APP1"), new HeaderEntry("device_mac", "MAC1")));

        EmbeddingConfigView view = service.create(req);

        assertEquals(List.of("app_id", "device_mac"), view.getCustomHeaderNames());
        assertFalse(mapper.valueToTree(view).toString().contains("MAC1"));
    }

    @Test
    void updateKeepsSavedValueWhenLeftBlankAndReplacesTheList() {
        EmbeddingConfig entity = new EmbeddingConfig();
        entity.setId("emb-1");
        entity.setModelName("m");
        when(repository.findById("emb-1")).thenReturn(Optional.of(entity));
        EmbeddingConfigRequest first = new EmbeddingConfigRequest();
        first.setCustomHeaders(List.of(new HeaderEntry("app_id", "APP1"), new HeaderEntry("device_mac", "MAC1")));
        service.update("emb-1", first);

        EmbeddingConfigRequest edit = new EmbeddingConfigRequest();
        edit.setCustomHeaders(List.of(new HeaderEntry("device_mac", ""), new HeaderEntry("X-Region", "cn")));
        service.update("emb-1", edit);

        assertEquals(Map.of("device_mac", "MAC1", "X-Region", "cn"), service.decryptHeaders(entity));

        EmbeddingConfigRequest clear = new EmbeddingConfigRequest();
        clear.setCustomHeaders(List.of());
        service.update("emb-1", clear);
        assertNull(entity.getCustomHeadersEncrypted());
    }

    @Test
    void invalidHeadersAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingConfigService.mergeHeaders(List.of(new HeaderEntry("Content-Type", "x")), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingConfigService.mergeHeaders(List.of(new HeaderEntry("bad name", "x")), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingConfigService.mergeHeaders(List.of(new HeaderEntry("X-A", "a\r\nInjected: 1")), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingConfigService.mergeHeaders(List.of(new HeaderEntry("app_id", "")), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingConfigService.mergeHeaders(List.of(new HeaderEntry("X-A", "1"), new HeaderEntry("x-a", "2")), Map.of()));
    }
}
