package com.example.agentplatform.assistant;

import com.example.agentplatform.model.AssistantAction;
import com.example.agentplatform.model.AssistantConversation;
import com.example.agentplatform.model.AssistantMessage;
import com.example.agentplatform.model.GuardrailPolicy;
import com.example.agentplatform.model.LlmProvider;
import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.security.guardrail.ContentGuardService;
import com.example.agentplatform.security.guardrail.InputGuardResult;
import com.example.agentplatform.security.guardrail.SensitiveWordMatcher;
import com.example.agentplatform.security.guardrail.StreamingSlidingWindowGuard;
import com.example.agentplatform.service.AuditRecorder;
import com.example.agentplatform.service.CustomHttpLlmClient;
import com.example.agentplatform.service.GuardrailPolicyService;
import com.example.agentplatform.service.LlmGatewayService;
import com.example.agentplatform.service.OpenAiCompatibleClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssistantServiceTest {

    @Mock LlmGatewayService gatewayService;
    @Mock OpenAiCompatibleClient openAiClient;
    @Mock CustomHttpLlmClient customHttpClient;
    @Mock AssistantConversationService conversationService;
    @Mock ContentGuardService contentGuardService;
    @Mock GuardrailPolicyService policyService;
    @Mock AuditRecorder auditRecorder;
    @Mock AssistantActionService actionService;
    @Mock PlatformDocsService platformDocsService;

    private final CurrentActor actor = new CurrentActor("u1", "dev", UserRole.DEVELOPER);
    private final List<String[]> events = new ArrayList<>();
    private AssistantService service;
    private AssistantService.Sink sink;
    private final List<String> toolArgs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        AssistantToolRegistry registry = new AssistantToolRegistry(List.of(
                AssistantTool.read("list_agents", "查询智能体列表",
                        "列出智能体", Map.of("type", "object", "properties", Map.of()), (args, ctx) -> {
                            toolArgs.add(ctx.actor().getUserId() + ":" + args);
                            return Map.of("ok", true, "total", 2);
                        }),
                AssistantTool.write("create_agent", "生成创建智能体操作",
                        "创建智能体", Map.of("type", "object", "properties", Map.of()), (args, ctx) -> {
                            AssistantAction action = new AssistantAction();
                            action.setId("act-1");
                            action.setConversationId(ctx.conversationId());
                            ctx.createdActions().add(action);
                            return Map.of("ok", true, "pendingActionId", "act-1");
                        })));
        service = new AssistantService(gatewayService, openAiClient, customHttpClient, registry, conversationService,
                contentGuardService, policyService, auditRecorder, actionService, platformDocsService, 1);
        when(actionService.statusNotes(any())).thenReturn(List.of());
        when(actionService.card(any())).thenAnswer(inv -> Map.of("id", ((AssistantAction) inv.getArgument(0)).getId()));
        when(platformDocsService.search(anyString(), anyInt())).thenReturn(List.of());
        sink = new AssistantService.Sink(new SseEmitter()) {
            @Override
            synchronized void send(String event, Object data) {
                events.add(new String[]{event, String.valueOf(data)});
            }
        };

        GuardrailPolicy policy = new GuardrailPolicy();
        when(policyService.getEffectivePolicy("u1")).thenReturn(policy);
        when(contentGuardService.inspectInput(any(), anyString()))
                .thenAnswer(inv -> InputGuardResult.allow(inv.getArgument(1), null, null));
        when(contentGuardService.createStreamingGuard(any())).thenReturn(new StreamingSlidingWindowGuard(null, false, false, 0));
        AssistantConversation conversation = new AssistantConversation();
        conversation.setId("asc-1");
        conversation.setUserId("u1");
        when(conversationService.openOrCreate(any(), anyString(), eq(actor))).thenReturn(conversation);
        when(conversationService.recentHistory(eq("asc-1"), anyInt())).thenReturn(List.of());
        when(conversationService.append(any(), any())).thenAnswer(inv -> {
            AssistantMessage m = inv.getArgument(1);
            m.setId("asm-" + m.getRole());
            return m;
        });

        LlmProvider provider = new LlmProvider();
        provider.setName("DeepSeek");
        provider.setBaseUrl("https://api.deepseek.com");
        provider.setDefaultModel("deepseek-v4-flash");
        when(gatewayService.resolveAssistantRoute()).thenReturn(Optional.of(new LlmGatewayService.AssistantRoute(
                new LlmGatewayService.ResolvedRoute(provider, null, 30000, 0, "sk-test", null), null, false)));
    }

    @Test
    void dedicatedAssistantModelOverridesChannelDefaultAndPageContextIsInSystemPrompt() {
        LlmProvider provider = new LlmProvider();
        provider.setName("Qwen");
        provider.setBaseUrl("https://dashscope.example.com");
        provider.setDefaultModel("qwen-plus");
        when(gatewayService.resolveAssistantRoute()).thenReturn(Optional.of(new LlmGatewayService.AssistantRoute(
                new LlmGatewayService.ResolvedRoute(provider, null, 30000, 0, "sk-test", null), "qwen-max", true)));
        AssistantQueryTools queryTools = org.mockito.Mockito.mock(AssistantQueryTools.class);
        when(queryTools.describeContext(any(), eq(actor))).thenReturn("用户正在查看智能体「售后客服」（ID agent-1）");
        service.setQueryTools(queryTools);
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new OpenAiCompatibleClient.StreamResult("好的", List.of(), 10, 2));

        service.converse(new AssistantChatRequest(null, "这个智能体怎么样", "AGENT",
                Map.of("page", "debug", "resourceType", "AGENT", "resourceId", "agent-1")), actor, sink);

        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
        verify(openAiClient).streamChat(anyString(), anyString(), eq("qwen-max"), any(), anyBoolean(),
                messages.capture(), any(), any(), anyInt(), any(), any());
        String system = String.valueOf(messages.getValue().get(0).get("content"));
        assertTrue(system.contains("【页面上下文】"));
        assertTrue(system.contains("agent-1"));
    }

    private List<String> eventNames() {
        return events.stream().map(e -> e[0]).toList();
    }

    private String payloadOf(String event) {
        return events.stream().filter(e -> e[0].equals(event)).map(e -> e[1]).reduce((a, b) -> b).orElse("");
    }

    @SuppressWarnings("unchecked")
    private static Consumer<String> contentCallback(org.mockito.invocation.InvocationOnMock inv) {
        return (Consumer<String>) inv.getArgument(9);
    }

    @Test
    void agentModeRunsToolThenStreamsFinalAnswerAndPersistsUsage() {
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new OpenAiCompatibleClient.StreamResult("", List.of(
                        new OpenAiCompatibleClient.ToolCall("call_1", "list_agents", "{\"status\":\"RUNNING\"}")), 100, 10))
                .thenAnswer(inv -> {
                    contentCallback(inv).accept("你有 ");
                    contentCallback(inv).accept("2 个运行中的智能体。");
                    return new OpenAiCompatibleClient.StreamResult("你有 2 个运行中的智能体。", List.of(), 150, 12);
                });

        service.converse(new AssistantChatRequest(null, "我有哪些运行中的智能体？", "AGENT"), actor, sink);

        assertEquals(List.of("start", "tool", "tool", "message", "message", "done"), eventNames());
        assertEquals(List.of("u1:{\"status\":\"RUNNING\"}"), toolArgs);

        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
        verify(openAiClient, times(2)).streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(),
                messages.capture(), any(), any(), anyInt(), any(), any());
        List<Map<String, Object>> secondRound = messages.getAllValues().get(1);
        assertEquals("tool", secondRound.get(secondRound.size() - 1).get("role"));
        assertEquals("call_1", secondRound.get(secondRound.size() - 1).get("tool_call_id"));

        ArgumentCaptor<AssistantMessage> saved = ArgumentCaptor.forClass(AssistantMessage.class);
        verify(conversationService, times(2)).append(any(), saved.capture());
        AssistantMessage reply = saved.getAllValues().get(1);
        assertEquals("你有 2 个运行中的智能体。", reply.getContent());
        assertEquals(250, reply.getPromptTokens());
        assertEquals(22, reply.getCompletionTokens());
        assertTrue(reply.getToolCalls().contains("list_agents"));
        assertTrue(payloadOf("done").contains("deepseek-v4-flash"));
    }

    @Test
    void chatModeNeverOffersTools() {
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), isNull(), any(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    contentCallback(inv).accept("答案");
                    return new OpenAiCompatibleClient.StreamResult("答案", List.of(), 1, 1);
                });

        service.converse(new AssistantChatRequest(null, "什么是知识库？", "CHAT"), actor, sink);

        verify(openAiClient).streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), isNull(), any(), anyInt(), any(), any());
        assertTrue(eventNames().contains("done"));
    }

    @Test
    void channelRejectingToolsFallsBackToChatModeWithNotice() {
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("HTTP 400 {\"error\":\"tools is not supported\"}"))
                .thenAnswer(inv -> {
                    contentCallback(inv).accept("请到智能体列表查看。");
                    return new OpenAiCompatibleClient.StreamResult("请到智能体列表查看。", List.of(), 1, 1);
                });

        service.converse(new AssistantChatRequest(null, "我有哪些智能体？", "AGENT"), actor, sink);

        assertTrue(payloadOf("done").contains(AssistantService.TOOLS_UNSUPPORTED_NOTICE));
        assertTrue(payloadOf("done").contains("请到智能体列表查看。"));
    }

    @Test
    void channelFailureBeforeAnyOutputReturnsFriendlyDegradedReply() {
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("HTTP 503 upstream unavailable"));

        service.converse(new AssistantChatRequest(null, "你好", "AGENT"), actor, sink);

        String done = payloadOf("done");
        assertTrue(done.contains(AssistantService.CHANNEL_FAILED_REPLY));
        assertTrue(done.contains("degraded=true"));
    }

    @Test
    void blockedInputIsRejectedAndNothingIsPersisted() {
        when(contentGuardService.inspectInput(any(), anyString()))
                .thenReturn(InputGuardResult.block("sensitive_input", "输入包含敏感词", "某敏感词"));

        service.converse(new AssistantChatRequest(null, "含敏感词的问题", "AGENT"), actor, sink);

        assertEquals(List.of("error"), eventNames());
        verify(conversationService, never()).append(any(), any());
        verify(auditRecorder).record(eq("assistant.input_blocked"), any(), any(), eq("BLOCKED"), any(), any(), any());
    }

    @Test
    void outputGuardInBlockModeStopsStreaming() {
        when(contentGuardService.createStreamingGuard(any()))
                .thenReturn(new StreamingSlidingWindowGuard(new SensitiveWordMatcher(List.of("违禁词")), true, true, 4));
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    contentCallback(inv).accept("这是一段很长的正常内容，");
                    contentCallback(inv).accept("然后出现违禁词");
                    contentCallback(inv).accept("之后的内容不应再输出");
                    return new OpenAiCompatibleClient.StreamResult("", List.of(), 1, 1);
                });

        service.converse(new AssistantChatRequest(null, "你好", "CHAT"), actor, sink);

        assertTrue(eventNames().contains("guardrail"));
        String shown = events.stream().filter(e -> e[0].equals("message")).map(e -> e[1]).reduce("", String::concat);
        assertFalse(shown.contains("违禁词"));
        assertFalse(shown.contains("之后的内容"));
        verify(auditRecorder, atLeastOnce()).record(eq("assistant.output_blocked"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void outputGuardInMaskModeKeepsStreamingMaskedText() {
        when(contentGuardService.createStreamingGuard(any()))
                .thenReturn(new StreamingSlidingWindowGuard(new SensitiveWordMatcher(List.of("违禁词")), true, false, 4));
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    contentCallback(inv).accept("前文违禁词后文");
                    return new OpenAiCompatibleClient.StreamResult("", List.of(), 1, 1);
                });

        service.converse(new AssistantChatRequest(null, "你好", "CHAT"), actor, sink);

        assertFalse(eventNames().contains("guardrail"));
        String shown = events.stream().filter(e -> e[0].equals("message")).map(e -> e[1]).reduce("", String::concat);
        assertTrue(shown.contains("后文"));
        assertFalse(shown.contains("违禁词"));
    }

    @Test
    void writeToolPushesActionCardAndAttachesItToTheReply() {
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new OpenAiCompatibleClient.StreamResult("", List.of(
                        new OpenAiCompatibleClient.ToolCall("call_1", "create_agent", "{\"name\":\"售后客服\"}")), 10, 5))
                .thenAnswer(inv -> {
                    contentCallback(inv).accept("已生成创建卡片，请确认。");
                    return new OpenAiCompatibleClient.StreamResult("已生成创建卡片，请确认。", List.of(), 20, 8);
                });

        service.converse(new AssistantChatRequest(null, "帮我建一个售后客服智能体", "AGENT"), actor, sink);

        assertEquals(List.of("start", "tool", "tool", "action", "message", "done"), eventNames());
        assertTrue(payloadOf("action").contains("act-1"));
        assertTrue(payloadOf("done").contains("actionIds=[act-1]"));
        ArgumentCaptor<java.util.Collection<AssistantAction>> attached = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(actionService).attachMessage(eq("asm-assistant"), attached.capture());
        assertEquals("act-1", attached.getValue().iterator().next().getId());
    }

    @Test
    void actionStatusNotesAreGivenToTheModelBeforeTheNewQuestion() {
        when(actionService.statusNotes("asc-1")).thenReturn(List.of("「创建智能体：售后客服」：用户已确认并执行成功（资源 ID agent-9）"));
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new OpenAiCompatibleClient.StreamResult("好的", List.of(), 1, 1));

        service.converse(new AssistantChatRequest("asc-1", "再给它绑定售后知识库", "AGENT"), actor, sink);

        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
        verify(openAiClient).streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(),
                messages.capture(), any(), any(), anyInt(), any(), any());
        List<Map<String, Object>> sent = messages.getValue();
        Map<String, Object> notes = sent.get(sent.size() - 2);
        assertEquals("system", notes.get("role"));
        assertTrue(notes.get("content").toString().contains("agent-9"));
        assertEquals("再给它绑定售后知识库", sent.get(sent.size() - 1).get("content"));
    }

    @Test
    void chatModeInjectsPlatformDocsIntoSystemPrompt() {
        when(platformDocsService.search(anyString(), anyInt())).thenReturn(List.of(new com.example.agentplatform.rag.RetrievedChunk(
                "c1", "d1", null, "platform-guide.md", null, null, null, null,
                "平台内置引擎必须先激活向量模型", null, 0.9, null, null, null, "HYBRID", 10, Map.of())));
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), isNull(), any(), anyInt(), any(), any()))
                .thenReturn(new OpenAiCompatibleClient.StreamResult("需要先激活向量模型", List.of(), 1, 1));

        service.converse(new AssistantChatRequest(null, "内置引擎知识库检索不到怎么办", "CHAT"), actor, sink);

        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
        verify(openAiClient).streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(),
                messages.capture(), isNull(), any(), anyInt(), any(), any());
        String system = messages.getValue().get(0).get("content").toString();
        assertTrue(system.contains("【平台使用文档参考】"));
        assertTrue(system.contains("平台内置引擎必须先激活向量模型"));
    }

    @Test
    void claimingCardsWithoutCallingWriteToolAddsNotice() {
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    contentCallback(inv).accept("两张待确认卡片已生成，请按顺序确认。");
                    return new OpenAiCompatibleClient.StreamResult("两张待确认卡片已生成，请按顺序确认。", List.of(), 1, 1);
                });

        service.converse(new AssistantChatRequest(null, "建一个智能体和一个知识库", "AGENT"), actor, sink);

        assertTrue(payloadOf("done").contains(AssistantService.CARD_CLAIM_NOTICE));
    }

    @Test
    void recognisesCardClaims() {
        assertTrue(AssistantService.claimsCardGenerated("两张待确认卡片已生成，请确认"));
        assertTrue(AssistantService.claimsCardGenerated("已为你生成创建智能体的操作卡片"));
        assertFalse(AssistantService.claimsCardGenerated("确认后我再为你生成绑定卡片"));
        assertFalse(AssistantService.claimsCardGenerated("有 3 个运行中的智能体"));
    }

    @Test
    void historyExcludesDegradedRepliesAndTruncatesLongMessages() {
        AssistantMessage oldUser = new AssistantMessage();
        oldUser.setRole("user");
        oldUser.setContent("x".repeat(AssistantService.HISTORY_MESSAGE_MAX_CHARS + 100));
        AssistantMessage degraded = new AssistantMessage();
        degraded.setRole("assistant");
        degraded.setContent(AssistantService.CHANNEL_FAILED_REPLY);
        degraded.setDegraded(true);

        var messages = AssistantService.buildMessages("sys", List.of(oldUser, degraded), "新问题");

        assertEquals(3, messages.size());
        assertEquals(AssistantService.HISTORY_MESSAGE_MAX_CHARS + 1, messages.get(1).get("content").toString().length());
        assertEquals("新问题", messages.get(2).get("content"));
    }

    @Test
    void recognisesToolRejectionErrors() {
        assertTrue(AssistantService.looksLikeToolsRejected("HTTP 400 {\"message\":\"Function calling is not supported\"}"));
        assertFalse(AssistantService.looksLikeToolsRejected("HTTP 401 invalid api key"));
        assertFalse(AssistantService.looksLikeToolsRejected("HTTP 500 tool server crashed"));
        assertFalse(AssistantService.looksLikeToolsRejected(null));
    }
}
