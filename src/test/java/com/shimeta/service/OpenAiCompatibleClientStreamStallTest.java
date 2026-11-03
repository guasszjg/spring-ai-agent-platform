package com.shimeta.service;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 模型通道发了一部分就不再发数据（连接不断）时，流式读取要超时中断，而不是一直卡住 */
class OpenAiCompatibleClientStreamStallTest {

    private final OpenAiCompatibleClient client = new OpenAiCompatibleClient(mock(OutboundUrlValidator.class));
    private final AtomicInteger requests = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);
    /** 第几次请求卡住（从 1 开始），0 表示每次都卡 */
    private volatile int stallOn;
    private volatile boolean keepAlive;
    private HttpServer server;
    private String baseUrl;

    private static final String TOOL_ROUND = "data: {\"choices\":[{\"delta\":{\"content\":\"天津\"}}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}}]}}]}\n\n"
            + "data: [DONE]\n\n";
    private static final String ANSWER = "data: {\"choices\":[{\"delta\":{\"content\":\"晴\"}}]}\n\ndata: [DONE]\n\n";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int n = requests.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                if (stallOn == 0 || n == stallOn) {
                    os.write("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    if (keepAlive) {
                        // 模拟通道繁忙排队：只发 keep-alive 注释行，一直不出内容
                        while (!release.await(100, TimeUnit.MILLISECONDS)) {
                            os.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        }
                    }
                    release.await(30, TimeUnit.SECONDS); // 卡住：连接保持但不再发数据
                    return;
                }
                os.write((n == 1 ? TOOL_ROUND : ANSWER).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // 客户端中断连接
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        client.streamIdleTimeoutMs = 600;
        client.streamFirstOutputTimeoutMs = 600;
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    @Test
    @DisplayName("单轮流式读取卡住：超时后抛出异常，并且不残留中断标记")
    void idleStreamTimesOut() {
        stallOn = 0;
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> client.streamChat(baseUrl, "sk", "m", null, false,
                List.of(Map.of("role", "user", "content", "hi")), null, null, 10_000, d -> { }, () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有开始输出");
        assertThat(System.currentTimeMillis() - start).isLessThan(5_000);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("通道排队只发 keep-alive 注释、一直不出内容：同样按空闲超时中断")
    void keepAliveOnlyStreamTimesOut() {
        stallOn = 0;
        keepAlive = true;
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> client.streamChat(baseUrl, "sk", "m", null, false,
                List.of(Map.of("role", "user", "content", "hi")), null, null, 10_000, d -> { }, () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有开始输出");
        assertThat(System.currentTimeMillis() - start).isLessThan(5_000);
    }

    @Test
    @DisplayName("工具执行后的轮次卡住：自动重试该轮，拿到回答")
    void retriesStalledRoundAfterTool() {
        stallOn = 2;
        AgentToolRegistry registry = mock(AgentToolRegistry.class);
        when(registry.execute("search", "{}")).thenReturn("晴");
        when(registry.formatToolCallSummary("search", "{}")).thenReturn("联网搜索");
        List<String> deltas = new ArrayList<>();

        var result = client.chatWithToolsStreaming(baseUrl, "sk", "m", null, false,
                List.of(Map.of("role", "user", "content", "天津天气")),
                List.of(Map.of("type", "function")), registry, null, 10_000, deltas::add, () -> false);

        assertThat(requests.get()).isEqualTo(3);
        assertThat(deltas).containsExactly("天津", "晴");
        assertThat(result.content()).isEqualTo("晴");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("调用方取消且读取卡住：不等空闲超时，尽快返回")
    void cancelWhileBlockedReturnsQuickly() {
        stallOn = 0;
        client.streamIdleTimeoutMs = 60_000;
        client.streamFirstOutputTimeoutMs = 60_000;
        long start = System.currentTimeMillis();
        boolean[] cancel = {false};
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            cancel[0] = true;
        }).start();

        var result = client.streamChat(baseUrl, "sk", "m", null, false,
                List.of(Map.of("role", "user", "content", "hi")), null, null, 10_000, d -> { }, () -> cancel[0]);

        assertThat(result.content()).isEmpty();
        assertThat(System.currentTimeMillis() - start).isLessThan(8_000);
    }
}
