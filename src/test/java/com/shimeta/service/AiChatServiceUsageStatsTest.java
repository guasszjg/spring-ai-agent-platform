package com.shimeta.service;

import com.shimeta.model.Agent;
import com.shimeta.model.AgentConversation;
import com.shimeta.model.ChatRequest;
import com.shimeta.model.ChatResponse;
import com.shimeta.model.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 对话的用量统计：切到备用通道、全部失败、流式中断时分别怎么记 */
class AiChatServiceUsageStatsTest {

    private final AgentService agentService = mock(AgentService.class);
    private final AgentConversationService conversationService = mock(AgentConversationService.class);
    private final LlmGatewayService gatewayService = mock(LlmGatewayService.class);
    private final OpenAiCompatibleClient openAiClient = mock(OpenAiCompatibleClient.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final LlmProvider primary = provider("智谱 GLM", "glm-5.3-flash");
    private final LlmProvider fallback = provider("DeepSeek", "deepseek-flash");
    private AiChatService service;

    private static LlmProvider provider(String name, String model) {
        LlmProvider p = new LlmProvider();
        p.setId(name);
        p.setName(name);
        p.setBaseUrl("http://" + model);
        p.setDefaultModel(model);
        return p;
    }

    private org.mockito.stubbing.OngoingStubbing<OpenAiCompatibleClient.ChatResult> blockingCall(String key) {
        return when(openAiClient.chatWithTools(anyString(), eq(key), anyString(), any(), anyBoolean(), any(), any(), any(), any(), anyInt(), anyInt()));
    }

    private org.mockito.stubbing.OngoingStubbing<OpenAiCompatibleClient.ChatResult> streamingCall(String key) {
        return when(openAiClient.chatWithToolsStreaming(anyString(), eq(key), anyString(), any(), anyBoolean(), any(), any(), any(), any(), anyInt(), any(), any(), anyInt()));
    }

    private static ChatRequest request() {
        ChatRequest request = new ChatRequest();
        request.setAgentId("a1");
        request.setMessage("你好");
        return request;
    }

    private static AiChatService.StreamSink sink(boolean cancelled) {
        return new AiChatService.StreamSink() {
            @Override
            public void onDelta(String text) {
            }

            @Override
            public boolean cancelled() {
                return cancelled;
            }
        };
    }

    @BeforeEach
    void setUp() {
        com.shimeta.security.CurrentActor.clear();
        Agent agent = new Agent();
        agent.setId("a1");
        agent.setName("客服");
        agent.setModelName("glm-5.3-flash");
        when(agentService.getById("a1")).thenReturn(Optional.of(agent));
        when(knowledgeBaseService.buildRetrieval(any(), any())).thenReturn(new KnowledgeBaseService.RetrievalContext("", List.of()));
        when(gatewayService.resolveRoute(any())).thenReturn(Optional.of(
                new LlmGatewayService.ResolvedRoute(primary, fallback, 1000, 0, "k1", "k2")));
        when(gatewayService.hasKey(fallback)).thenReturn(true);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        when(conversationService.appendTurn(any(), any(), any(), any(), any(), any(), anyLong(), anyInt(), any())).thenReturn(conversation);
        service = new AiChatService(agentService, conversationService, gatewayService, openAiClient,
                mock(CustomHttpLlmClient.class), mock(com.shimeta.tool.AgentToolRegistry.class), mock(AgentToolSecretService.class),
                null, knowledgeBaseService, mock(ResourceAuthorizationService.class), null, false);
    }

    @Test
    @DisplayName("切到备用通道：返回主通道名称，记一次切换，不改写智能体的模型设置")
    void failoverIsReportedAndKeepsAgentModel() {
        blockingCall("k1").thenThrow(new IllegalStateException("HTTP 400"));
        blockingCall("k2").thenReturn(new OpenAiCompatibleClient.ChatResult("好的", 100, 20, 120));

        ChatResponse response = service.chat(request());

        assertThat(response.getModel()).isEqualTo("deepseek-flash");
        assertThat(response.getFailoverFrom()).isEqualTo("智谱 GLM");
        assertThat(response.getPromptTokens()).isEqualTo(100);
        assertThat(response.getCompletionTokens()).isEqualTo(20);
        verify(agentService).recordInvocation(eq("a1"), anyLong(), eq(100L), eq(20L), eq(true), eq(true), eq(0L));
        verify(agentService, never()).recordExecutedModel(any(), any());
    }

    @Test
    @DisplayName("主通道成功：没有切换标记，正常记录实际模型")
    void normalCallHasNoFailover() {
        blockingCall("k1").thenReturn(new OpenAiCompatibleClient.ChatResult("好的", 100, 20, 120));

        ChatResponse response = service.chat(request());

        assertThat(response.getFailoverFrom()).isNull();
        verify(agentService).recordInvocation(eq("a1"), anyLong(), eq(100L), eq(20L), eq(true), eq(false), eq(0L));
        verify(agentService).recordExecutedModel("a1", "glm-5.3-flash");
    }

    @Test
    @DisplayName("主备通道都失败：抛错前记一次失败调用（否则成功率偏高）")
    void totalFailureIsRecorded() {
        blockingCall("k1").thenThrow(new IllegalStateException("HTTP 500"));
        blockingCall("k2").thenThrow(new IllegalStateException("HTTP 500"));

        assertThatThrownBy(() -> service.chat(request())).isInstanceOf(IllegalStateException.class);

        verify(agentService).recordInvocation(eq("a1"), anyLong(), eq(0L), eq(0L), eq(false), eq(true), eq(0L));
    }

    @Test
    @DisplayName("输出到一半通道中断：按文本长度估算 Token，记一次失败调用")
    void streamInterruptedIsEstimated() {
        streamingCall("k1").thenAnswer(inv -> {
            java.util.function.Consumer<String> onContent = inv.getArgument(10);
            onContent.accept("你好世界");
            throw new IllegalStateException("连接断开");
        });

        assertThatThrownBy(() -> service.chat(request(), sink(false)))
                .isInstanceOf(AiChatService.StreamInterruptedException.class);

        verify(agentService).recordInvocation(eq("a1"), anyLong(), longThat(p -> p > 0), eq(4L), eq(false), eq(false), eq(0L));
    }

    @Test
    @DisplayName("调用方中途断开：通道没返回用量时按文本长度估算")
    void cancelledStreamIsEstimated() {
        streamingCall("k1").thenReturn(new OpenAiCompatibleClient.ChatResult("你好世界", 0, 0, 0));

        service.chat(request(), sink(true));

        verify(agentService).recordInvocation(eq("a1"), anyLong(), longThat(p -> p > 0), eq(4L), eq(true), eq(false), eq(0L));
    }

    private AiChatService serviceWithDefaultClient(org.springframework.ai.chat.model.ChatModel chatModel,
                                                   OpenAiCompatibleClient client) {
        return new AiChatService(agentService, conversationService, gatewayService, client,
                mock(CustomHttpLlmClient.class), mock(com.shimeta.tool.AgentToolRegistry.class), mock(AgentToolSecretService.class),
                null, knowledgeBaseService, mock(ResourceAuthorizationService.class), chatModel, false);
    }

    @Test
    @DisplayName("通道返回空内容：提示模型没有返回内容，不回退到默认客户端，记一次失败调用")
    void emptyReplyDoesNotFallBackToDefaultClient() {
        var chatModel = mock(org.springframework.ai.chat.model.ChatModel.class);
        streamingCall("k1").thenReturn(new OpenAiCompatibleClient.ChatResult("", 10, 20, 30));

        assertThatThrownBy(() -> serviceWithDefaultClient(chatModel, openAiClient).chat(request(), sink(false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型没有返回内容");

        verify(chatModel, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        verify(agentService).recordInvocation(eq("a1"), anyLong(), eq(0L), eq(0L), eq(false), eq(false), eq(0L));
    }

    @Test
    @DisplayName("主备通道都失败：提示通道调用失败，不回退到默认客户端")
    void channelFailureDoesNotFallBackToDefaultClient() {
        var chatModel = mock(org.springframework.ai.chat.model.ChatModel.class);
        blockingCall("k1").thenThrow(new IllegalStateException("HTTP 500"));
        blockingCall("k2").thenThrow(new IllegalStateException("HTTP 500"));

        assertThatThrownBy(() -> serviceWithDefaultClient(chatModel, openAiClient).chat(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型通道调用失败");

        verify(chatModel, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test
    @DisplayName("没有配置任何通道：仍可使用默认客户端")
    void noRouteStillUsesDefaultClient() {
        var chatModel = mock(org.springframework.ai.chat.model.ChatModel.class);
        when(gatewayService.resolveRoute(any())).thenReturn(Optional.empty());
        when(chatModel.getOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(
                new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage("默认客户端的回答")))));

        ChatResponse response = serviceWithDefaultClient(chatModel, openAiClient).chat(request());

        assertThat(response.getReply()).isEqualTo("默认客户端的回答");
    }

    @Test
    @DisplayName("真实客户端读到空的流式响应：提示模型没有返回内容")
    void emptySseFromRealClientIsReported() throws Exception {
        var chatModel = mock(org.springframework.ai.chat.model.ChatModel.class);
        byte[] sse = ("data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, sse.length);
            exchange.getResponseBody().write(sse);
            exchange.close();
        });
        server.start();
        try {
            primary.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            OpenAiCompatibleClient realClient = new OpenAiCompatibleClient(mock(com.shimeta.config.OutboundUrlValidator.class));

            assertThatThrownBy(() -> serviceWithDefaultClient(chatModel, realClient).chat(request(), sink(false)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("模型没有返回内容");

            verify(chatModel, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Token 估算：中文每字约 1 个，其余每 4 个字符约 1 个")
    void estimateTokens() {
        assertThat(AiChatService.estimateTokens(null)).isZero();
        assertThat(AiChatService.estimateTokens("你好")).isEqualTo(2);
        assertThat(AiChatService.estimateTokens("abcdefgh")).isEqualTo(2);
        assertThat(AiChatService.estimateTokens("你好abcd")).isEqualTo(3);
    }
}
