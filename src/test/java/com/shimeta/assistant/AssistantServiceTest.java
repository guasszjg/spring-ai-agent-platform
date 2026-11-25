package com.shimeta.assistant;

import com.shimeta.model.AssistantAction;
import com.shimeta.model.AssistantConversation;
import com.shimeta.model.AssistantMessage;
import com.shimeta.model.GuardrailPolicy;
import com.shimeta.model.LlmProvider;
import com.shimeta.model.UserRole;
import com.shimeta.security.CurrentActor;
import com.shimeta.security.guardrail.ContentGuardService;
import com.shimeta.security.guardrail.InputGuardResult;
import com.shimeta.security.guardrail.SensitiveWordMatcher;
import com.shimeta.security.guardrail.StreamingSlidingWindowGuard;
import com.shimeta.service.AuditRecorder;
import com.shimeta.service.CustomHttpLlmClient;
import com.shimeta.service.GuardrailPolicyService;
import com.shimeta.service.LlmGatewayService;
import com.shimeta.service.OpenAiCompatibleClient;
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
        when(platformDocsService.search(anyString(), anyInt(), any())).thenReturn(List.of());
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
        verify(openAiClient, atLeastOnce()).streamChat(anyString(), anyString(), eq("qwen-max"), any(), anyBoolean(),
                messages.capture(), any(), any(), anyInt(), any(), any());
        String system = String.valueOf(messages.getAllValues().get(0).get(0).get("content"));
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
        // 直接带上真实失败原因
        assertTrue(done.contains("失败原因："));
        assertTrue(done.contains("HTTP 503 upstream unavailable"));
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
        // 卡片状态必须并入首条 system 消息，禁止在对话中间出现第二条 system（兼容 Qwen3 / vLLM Jinja 模板）
        assertEquals("system", sent.get(0).get("role"));
        assertTrue(sent.get(0).get("content").toString().contains("agent-9"));
        assertEquals(1, sent.stream().filter(m -> "system".equals(m.get("role"))).count());
        assertEquals("再给它绑定售后知识库", sent.get(sent.size() - 1).get("content"));
    }

    @Test
    void chatModeInjectsPlatformDocsIntoSystemPrompt() {
        when(platformDocsService.search(anyString(), anyInt(), eq(actor))).thenReturn(List.of(new com.shimeta.rag.RetrievedChunk(
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

    @Test
    void readOnlyQuestionsOmitWriteToolsToSaveContext() {
        assertFalse(AssistantService.mayNeedWriteTools("技能库里有哪些技能？", List.of()));
        assertFalse(AssistantService.mayNeedWriteTools("使用最多的模型是哪个？", List.of()));
        assertTrue(AssistantService.mayNeedWriteTools("帮我新建一个售后客服智能体", List.of()));
        assertTrue(AssistantService.mayNeedWriteTools("名字改成退货助手", List.of()));
        assertTrue(AssistantService.mayNeedWriteTools("那刚才那个呢", List.of("「创建智能体」已确认")));

        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new OpenAiCompatibleClient.StreamResult("当前有 2 个技能", List.of(), 100, 20));

        service.converse(new AssistantChatRequest(null, "技能库里有哪些技能？", "AGENT"), actor, sink);

        ArgumentCaptor<List<Map<String, Object>>> toolsCap = ArgumentCaptor.forClass(List.class);
        verify(openAiClient, atLeastOnce()).streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(),
                any(), toolsCap.capture(), any(), anyInt(), any(), any());
        String sentTools = String.valueOf(toolsCap.getAllValues().get(0));
        assertTrue(sentTools.contains("list_agents"));
        assertFalse(sentTools.contains("create_agent"));
    }

    @Test
    void smallContextWindowTrimsOldestHistoryKeepingSystemAndQuestion() {
        LlmProvider ollama = new LlmProvider();
        ollama.setProtocol(com.shimeta.model.LlmProtocolType.OLLAMA);
        assertEquals(AssistantService.OLLAMA_DEFAULT_CONTEXT_WINDOW, AssistantService.effectiveContextWindow(ollama));

        List<Map<String, Object>> base = new ArrayList<>();
        base.add(Map.of("role", "system", "content", "系统提示"));
        for (int i = 0; i < 6; i++) {
            base.add(Map.of("role", "user", "content", "历史提问" + i + "：" + "长文本内容".repeat(120)));
            base.add(Map.of("role", "assistant", "content", "历史回答" + i + "：" + "详细分析内容".repeat(120)));
        }
        base.add(Map.of("role", "user", "content", "技能库里有哪些技能？"));

        List<Map<String, Object>> fitted = service.fitMessagesForWindow(base, List.of(), 4096);

        assertTrue(fitted.size() < base.size(), "超长历史应被裁剪");
        assertEquals("系统提示", fitted.get(0).get("content"));
        assertEquals("技能库里有哪些技能？", fitted.get(fitted.size() - 1).get("content"));
        if (fitted.size() > 2) {
            assertEquals("user", fitted.get(1).get("role"));
        }
    }

    @Test
    void selectReadToolsFocusesOnRelevantDomainForSmallModels() {
        assertEquals(java.util.Set.of("list_skills"), AssistantService.selectReadTools("技能库里有哪些技能？", null));
        assertEquals(java.util.Set.of("list_mcp_servers"), AssistantService.selectReadTools("平台有哪些 MCP 服务？", null));
        var usageTools = AssistantService.selectReadTools("使用最多的模型是哪个？", null);
        assertTrue(usageTools.contains("get_usage_summary"));
        assertTrue(usageTools.size() <= 3);
        // 泛问/未指明模块时返回 null（下发全量只读工具）
        assertEquals(null, AssistantService.selectReadTools("「售后客服」用的是什么模型？", null));
    }

    @Test
    void chatModeDocsBudgetScalesWithChannelContextWindow() {
        var small = AssistantService.docsBudgetForWindow(4096);
        var medium = AssistantService.docsBudgetForWindow(8192);
        var normal = AssistantService.docsBudgetForWindow(null);
        var large = AssistantService.docsBudgetForWindow(65536);

        assertEquals(2, small.topK());
        assertEquals(3, medium.topK());
        assertEquals(4, normal.topK());
        assertEquals(6, large.topK());
        assertTrue(small.maxTotalChars() < medium.maxTotalChars());
        assertTrue(normal.maxTotalChars() < large.maxTotalChars());
    }

    @Test
    void postTurnVerifierStripsFabricatedLinksAndFlagsPrematureCompletion() {
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new OpenAiCompatibleClient.StreamResult("", List.of(
                        new OpenAiCompatibleClient.ToolCall("call_1", "create_agent", "{}")), 80, 10))
                .thenAnswer(inv -> {
                    String text = "已为你创建成功，可以去 [编造入口](/debug/fake-id) 查看。";
                    contentCallback(inv).accept(text);
                    return new OpenAiCompatibleClient.StreamResult(text, List.of(), 90, 20);
                });

        service.converse(new AssistantChatRequest(null, "帮我创建一个客服智能体", "AGENT"), actor, sink);

        ArgumentCaptor<AssistantMessage> saved = ArgumentCaptor.forClass(AssistantMessage.class);
        verify(conversationService, times(2)).append(any(), saved.capture());
        AssistantMessage reply = saved.getAllValues().get(1);
        assertFalse(reply.getContent().contains("/debug/fake-id"), "编造的链接应被去掉");
        assertTrue(reply.getContent().contains("编造入口"), "去掉链接后应保留文字");
        assertTrue(payloadOf("done").contains("尚未执行"), "卡片未确认却宣称创建成功时应附加提醒");
        assertTrue(payloadOf("done").contains(AssistantAnswerVerifier.FLAG_FABRICATED_LINK));
        assertTrue(payloadOf("done").contains(AssistantAnswerVerifier.FLAG_PREMATURE_COMPLETION));
    }

    @Test
    void readDataQuerySkippingToolsRetriesOnceWithToolChoiceRequiredWithoutDuplicateText() {
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), anyInt(), any(), any()))
                // 第 1 次：跳过调工具直接张嘴编造（应被缓冲不推给前端）
                .thenAnswer(inv -> {
                    contentCallback(inv).accept("我猜你有 5 个智能体。");
                    return new OpenAiCompatibleClient.StreamResult("我猜你有 5 个智能体。", List.of(), 50, 10);
                })
                // 第 2 次：带 tool_choice=required 重试，模型正确调用 list_agents
                .thenReturn(new OpenAiCompatibleClient.StreamResult("", List.of(
                        new OpenAiCompatibleClient.ToolCall("call_retry", "list_agents", "{}")), 60, 12))
                // 第 3 次：拿到工具结果后输出真实回复
                .thenAnswer(inv -> {
                    contentCallback(inv).accept("你当前共有 2 个智能体。");
                    return new OpenAiCompatibleClient.StreamResult("你当前共有 2 个智能体。", List.of(), 90, 15);
                });

        service.converse(new AssistantChatRequest(null, "我有哪些智能体？", "AGENT"), actor, sink);

        ArgumentCaptor<com.shimeta.model.ChatGeneration> genCap = ArgumentCaptor.forClass(com.shimeta.model.ChatGeneration.class);
        verify(openAiClient, times(3)).streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(),
                any(), any(), genCap.capture(), anyInt(), any(), any());
        assertEquals(null, genCap.getAllValues().get(0).getToolChoice());
        assertEquals("required", genCap.getAllValues().get(1).getToolChoice());
        assertEquals(null, genCap.getAllValues().get(2).getToolChoice());

        String shown = events.stream().filter(e -> e[0].equals("message")).map(e -> e[1]).reduce("", String::concat);
        assertFalse(shown.contains("我猜你有 5 个智能体"), "被缓冲的未调工具草稿不得推送给前端");
        assertTrue(shown.contains("你当前共有 2 个智能体。"));
    }

    @Test
    void toolChoiceRequiredRejectionFlushesInitialBufferedReplyGracefully() {
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    contentCallback(inv).accept("初版回答内容。");
                    return new OpenAiCompatibleClient.StreamResult("初版回答内容。", List.of(), 40, 8);
                })
                .thenThrow(new IllegalStateException("HTTP 400 {\"error\":\"tool_choice required is not supported\"}"));

        service.converse(new AssistantChatRequest(null, "我有哪些智能体？", "AGENT"), actor, sink);

        String shown = events.stream().filter(e -> e[0].equals("message")).map(e -> e[1]).reduce("", String::concat);
        assertTrue(shown.contains("初版回答内容。"), "重试被通道拒绝时应平滑吐出初版缓冲文本");
        assertTrue(payloadOf("done").contains("degraded=false"));
    }

    @Test
    void under7bModelAutomaticallyDowngradesAgentModeToChatModeWithNotice() {
        LlmProvider smallOllama = new LlmProvider();
        smallOllama.setName("Board Ollama 1.7B");
        smallOllama.setProtocol(com.shimeta.model.LlmProtocolType.OLLAMA);
        smallOllama.setBaseUrl("http://127.0.0.1:11434/v1");
        smallOllama.setDefaultModel("qwen3:1.7b");
        smallOllama.setSupportsTools(true);
        // Ollama 默认 modelSizeTier 为 SMALL_UNDER_7B，应自动降级为问答模式
        when(gatewayService.resolveAssistantRoute()).thenReturn(Optional.of(new LlmGatewayService.AssistantRoute(
                new LlmGatewayService.ResolvedRoute(smallOllama, null, 30000, 0, "", null), null, false)));
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), isNull(), any(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    contentCallback(inv).accept("请到智能体列表查看。");
                    return new OpenAiCompatibleClient.StreamResult("请到智能体列表查看。", List.of(), 20, 10);
                });

        service.converse(new AssistantChatRequest(null, "我有哪些智能体？", "AGENT"), actor, sink);

        verify(openAiClient).streamChat(anyString(), anyString(), eq("qwen3:1.7b"), any(), anyBoolean(),
                any(), isNull(), any(), anyInt(), any(), any());
        assertTrue(payloadOf("done").contains("mode=CHAT"));
        assertTrue(payloadOf("done").contains("7B 以上"));
    }

    @Test
    void writeRequestClaimingCardWithoutToolCallRetriesAndGeneratesRealCard() {
        when(actionService.statusNotes("asc-1")).thenReturn(List.of("「创建智能体：test01」：等待用户确认（pendingActionId act-old）"));
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), anyInt(), any(), any()))
                // 第 1 次：误以为已有待确认卡片就跳过调工具并口头声称卡片已生成（应被缓冲并触发纠正重试）
                .thenAnswer(inv -> {
                    String bad = "「创建智能体：test01」的操作卡片已经生成，目前正等待你确认，无需重复创建。";
                    contentCallback(inv).accept(bad);
                    return new OpenAiCompatibleClient.StreamResult(bad, List.of(), 50, 12);
                })
                // 第 2 次：纠正重试后模型真正调用 create_agent
                .thenReturn(new OpenAiCompatibleClient.StreamResult("", List.of(
                        new OpenAiCompatibleClient.ToolCall("call_create", "create_agent", "{\"name\":\"test01\"}")), 60, 15))
                // 第 3 次：生成最终回复
                .thenAnswer(inv -> {
                    contentCallback(inv).accept("已为你重新生成创建 test01 的待确认卡片，请点击确认。");
                    return new OpenAiCompatibleClient.StreamResult("已为你重新生成创建 test01 的待确认卡片，请点击确认。", List.of(), 80, 18);
                });

        service.converse(new AssistantChatRequest("asc-1", "帮我创建一个 test01 的智能体", "AGENT"), actor, sink);

        ArgumentCaptor<List<Map<String, Object>>> msgCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<com.shimeta.model.ChatGeneration> genCap = ArgumentCaptor.forClass(com.shimeta.model.ChatGeneration.class);
        verify(openAiClient, times(3)).streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(),
                msgCap.capture(), any(), genCap.capture(), anyInt(), any(), any());
        // 初次请求的历史卡片提示应明确允许对未执行卡片重新生成
        String firstNotes = msgCap.getAllValues().get(0).stream()
                .map(m -> String.valueOf(m.get("content")))
                .filter(c -> c.contains("act-old"))
                .findFirst().orElse("");
        assertTrue(firstNotes.contains("必须重新调用对应的写工具"));
        // 重试请求携带系统纠正（并入首条 system，不新增尾部 system）与 tool_choice=required
        assertEquals("required", genCap.getAllValues().get(1).getToolChoice());
        List<Map<String, Object>> retrySent = msgCap.getAllValues().get(1);
        assertEquals(1, retrySent.stream().filter(m -> "system".equals(m.get("role"))).count());
        assertTrue(retrySent.get(0).get("content").toString().contains("系统纠正"));
        // 前端未收到口头误报草稿，且收到了真实 action 卡片、无误报 notice
        String shown = events.stream().filter(e -> e[0].equals("message")).map(e -> e[1]).reduce("", String::concat);
        assertFalse(shown.contains("无需重复创建"));
        assertTrue(shown.contains("重新生成创建 test01"));
        assertTrue(payloadOf("action").contains("act-1"));
        assertFalse(payloadOf("done").contains(AssistantService.CARD_CLAIM_NOTICE));
    }

    @Test
    void streamingRepetitionLoopIsDetectedTruncatedAndAbortsUpstreamStream() {
        String repeatedBlock = "在的！我是 AgentMatrix 平台的 AI 助手，可以帮你查询智能体、排查问题、查看用量和知识库等。你现在在智能体列表页面，有什么需要帮忙的，直接说就行。你想做什么？";
        when(openAiClient.streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    Consumer<String> onContent = contentCallback(inv);
                    java.util.function.BooleanSupplier cancelled = inv.getArgument(10);
                    onContent.accept(repeatedBlock + "\n\n");
                    assertFalse(cancelled.getAsBoolean());
                    onContent.accept(repeatedBlock + "\n\n");
                    assertTrue(cancelled.getAsBoolean(), "检测到连续复读后应立即触发上游流中止信号");
                    onContent.accept(repeatedBlock);
                    return new OpenAiCompatibleClient.StreamResult(repeatedBlock, List.of(), 50, 120);
                });

        service.converse(new AssistantChatRequest(null, "在吗?", "AGENT", Map.of("page", "agents")), actor, sink);

        ArgumentCaptor<AssistantMessage> saved = ArgumentCaptor.forClass(AssistantMessage.class);
        verify(conversationService, times(2)).append(any(), saved.capture());
        String finalText = saved.getAllValues().get(1).getContent();
        assertEquals(repeatedBlock, finalText, "最终保存与 done 事件应只保留首份，裁掉重复尾部");
        // 且「在吗?」不应触发 tool_choice=required 重试，仅调用模型 1 次
        verify(openAiClient, times(1)).streamChat(anyString(), anyString(), anyString(), any(), anyBoolean(),
                any(), any(), any(), anyInt(), any(), any());
    }
}
