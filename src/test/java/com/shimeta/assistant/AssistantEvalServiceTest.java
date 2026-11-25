package com.shimeta.assistant;

import com.shimeta.model.AssistantAction;
import com.shimeta.model.UserRole;
import com.shimeta.repository.AssistantActionRepository;
import com.shimeta.repository.AssistantConversationRepository;
import com.shimeta.repository.AssistantEvalRunRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.AgentService;
import com.shimeta.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssistantEvalServiceTest {

    @Mock AssistantService assistantService;
    @Mock AgentService agentService;
    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock AssistantActionRepository actionRepository;
    @Mock AssistantConversationRepository conversationRepository;
    @Mock AssistantEvalRunRepository runRepository;

    private AssistantEvalService service;

    @BeforeEach
    void setUp() {
        service = new AssistantEvalService(assistantService, agentService, knowledgeBaseService,
                actionRepository, conversationRepository, runRepository);
    }

    @Test
    void bundledCasesLoadCoverAllCapabilitiesAndOnlyReferenceExistingTools() {
        Set<String> known = new HashSet<>(AssistantEvalScorer.WRITE_TOOLS);
        known.addAll(List.of("list_agents", "get_agent_detail", "list_knowledge_bases", "list_knowledge_documents",
                "list_mcp_servers", "list_skills", "get_gateway_status", "get_usage_summary", "diagnose_agent",
                "list_templates", "search_platform_docs", "test_gateway_channel",
                "list_resources", "get_resource", "run_retrieval_test", "get_agent_logs", "get_tool_usage",
                "list_share_links"));
        List<AssistantEvalCase> cases = service.cases();

        assertTrue(cases.size() >= 250, "扩充后评测用例数量应不少于 250，实际 " + cases.size());
        Set<String> ids = new HashSet<>();
        Map<String, Integer> capCount = new LinkedHashMap<>();
        Set<String> capWithParaphrase = new HashSet<>();
        Set<String> capWithViewerDenied = new HashSet<>();
        for (AssistantEvalCase c : cases) {
            assertTrue(ids.add(c.id()), "用例 ID 重复：" + c.id());
            assertTrue(c.question() != null && !c.question().isBlank(), c.id() + " 缺少问题");
            assertTrue(c.expect() != null, c.id() + " 缺少期望");
            if (c.capabilityId() != null) {
                capCount.merge(c.capabilityId(), 1, Integer::sum);
                if ("paraphrase".equals(c.variant())) {
                    capWithParaphrase.add(c.capabilityId());
                }
                if ("VIEWER".equals(c.role()) && "viewer_denied".equals(c.variant())) {
                    capWithViewerDenied.add(c.capabilityId());
                }
            }
            var e = c.expect();
            for (List<String> tools : java.util.Arrays.asList(e.toolsAll(), e.toolsAny(), e.toolsNone())) {
                for (String tool : AssistantEvalScorer.expand(tools)) {
                    assertTrue(known.contains(tool), c.id() + " 引用了不存在的工具：" + tool);
                }
            }
            if (e.args() != null) {
                e.args().forEach(a -> {
                    assertTrue(known.contains(a.tool()), c.id() + " 引用了不存在的工具：" + a.tool());
                    Pattern.compile(AssistantEvalService.substitute(a.pattern(),
                            Map.of("agent", "x", "kb", "y", "agent_id", "a1", "kb_id", "k1",
                                    "agent_count", "3", "kb_count", "2", "kb_doc_count", "5"), true));
                });
            }
        }

        // 4.7 验收守门：capabilities.json 100% 覆盖（每条 ≥ 2 用例且含同义改写，每个 WRITE 条目另带 VIEWER 越权用例）
        for (AssistantCapabilities.CapabilityEntry entry : AssistantCapabilities.defaultInstance().capabilities()) {
            int count = capCount.getOrDefault(entry.id(), 0);
            assertTrue(count >= 2, "能力条目 " + entry.id() + " 评测用例不足 2 条（实际 " + count + "）");
            assertTrue(capWithParaphrase.contains(entry.id()), "能力条目 " + entry.id() + " 缺少同义/口语改写用例");
            if ("WRITE".equals(entry.handling())) {
                assertTrue(capWithViewerDenied.contains(entry.id()), "WRITE 能力条目 " + entry.id() + " 缺少只读观察员越权用例");
            }
        }
    }

    @Test
    void placeholdersAreQuotedInRegexButPlainInText() {
        Map<String, String> values = Map.of("agent", "SQL & DB 调优(大师)");

        assertEquals("「SQL & DB 调优(大师)」停用", AssistantEvalService.substitute("「{{agent}}」停用", values, false));
        String regex = AssistantEvalService.substitute("{{agent}}", values, true);
        assertTrue(Pattern.compile(regex).matcher("诊断 SQL & DB 调优(大师)").find());
        assertEquals("{{kb}} 未知", AssistantEvalService.substitute("{{kb}} 未知", values, false));
    }

    @Test
    void caseWithUnresolvablePlaceholderIsSkipped() {
        var c = new AssistantEvalCase("x", "数据查询", "SUPER_ADMIN", null, null, "「{{kb}}」有几篇文档？", null,
                new AssistantEvalCase.Expect(null, null, null, null, null, null, null, null, null, null, null));

        Map<String, Object> result = service.runCase(c, Map.of("year", "2026"));

        assertEquals("SKIPPED", result.get("status"));
    }

    @Test
    void runsCaseAsVirtualRoleScoresAndCancelsGeneratedCards() {
        var c = new AssistantEvalCase("w", "创建修改", "SUPER_ADMIN", null, null, "帮我建一个客服智能体", null,
                new AssistantEvalCase.Expect(List.of("create_agent"), null, null, null, null, 1, 1,
                        null, List.of("确认"), null, null));
        when(assistantService.converse(any(), any(), any())).thenAnswer(inv -> {
            CurrentActor actor = inv.getArgument(1);
            AssistantService.Sink sink = inv.getArgument(2);
            ToolContext ctx = ToolContext.of(actor, "asc-eval");
            ctx.calls().add(new ToolContext.ToolCallRecord("create_agent", "{\"name\":\"客服\"}", true));
            AssistantAction action = new AssistantAction();
            action.setId("act-eval");
            action.setToolName("create_agent");
            ctx.createdActions().add(action);
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("content", "已生成卡片，请确认");
            done.put("model", "deepseek-flash");
            done.put("promptTokens", 100);
            done.put("completionTokens", 20);
            done.put("degraded", false);
            sink.send("done", done);
            return ctx;
        });

        Map<String, Object> result = service.runCase(c, Map.of());

        assertEquals("PASSED", result.get("status"), String.valueOf(result.get("failures")));
        ArgumentCaptor<CurrentActor> actor = ArgumentCaptor.forClass(CurrentActor.class);
        verify(assistantService).converse(any(), actor.capture(), any());
        assertTrue(actor.getValue().getUserId().startsWith(AssistantEvalService.EVAL_USER_PREFIX));
        assertEquals(UserRole.SUPER_ADMIN, actor.getValue().getRole());
        verify(actionRepository).cancel(eq("act-eval"), eq(actor.getValue().getUserId()));
        assertNull(CurrentActor.get());
    }

    @Test
    void degradedReplyIsRecordedAsErrorNotFailure() {
        var c = new AssistantEvalCase("q", "数据查询", "VIEWER", null, null, "有哪些智能体", null,
                new AssistantEvalCase.Expect(List.of("list_agents"), null, null, null, null, null, null, null, null, null, null));
        when(assistantService.converse(any(), any(), any())).thenAnswer(inv -> {
            AssistantService.Sink sink = inv.getArgument(2);
            sink.send("done", Map.of("content", "模型通道暂时没有返回结果", "degraded", true));
            return ToolContext.of(inv.getArgument(1), "asc-eval");
        });

        Map<String, Object> result = service.runCase(c, Map.of());

        assertEquals("ERROR", result.get("status"));
    }

    @Test
    void summaryPassRateExcludesSkippedAndErrors() {
        Map<String, Object> summary = AssistantEvalService.summarize(List.of(
                Map.of("category", "查询", "status", "PASSED"),
                Map.of("category", "查询", "status", "FAILED"),
                Map.of("category", "安全", "status", "PASSED"),
                Map.of("category", "安全", "status", "SKIPPED"),
                Map.of("category", "安全", "status", "ERROR")));

        assertEquals(66.7, summary.get("passRate"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Integer>> byCategory = (Map<String, Map<String, Integer>>) summary.get("byCategory");
        assertEquals(3, byCategory.get("安全").get("total"));
        assertEquals(1, byCategory.get("安全").get("errored"));
    }

    @Test
    void virtualActorsUseDedicatedIdsForCleanup() {
        assertEquals(UserRole.VIEWER, AssistantEvalService.actorFor("VIEWER").getRole());
        assertEquals("assistant-eval-developer", AssistantEvalService.actorFor("DEVELOPER").getUserId());
        assertEquals(UserRole.SUPER_ADMIN, AssistantEvalService.actorFor("unknown").getRole());
    }

    @Test
    void channelComparisonReviewQueueAndModelSizeTierGatingWork() {
        com.shimeta.service.LlmGatewayService gw = org.mockito.Mockito.mock(com.shimeta.service.LlmGatewayService.class);
        com.shimeta.repository.AssistantMessageRepository msgRepo = org.mockito.Mockito.mock(com.shimeta.repository.AssistantMessageRepository.class);
        service.setGatewayService(gw);
        service.setMessageRepository(msgRepo);

        // 1. 按通道对比
        com.shimeta.model.AssistantEvalRun run1 = new com.shimeta.model.AssistantEvalRun();
        run1.setId("eval-ds");
        run1.setStatus(com.shimeta.model.AssistantEvalRun.COMPLETED);
        run1.setChannelId("prov-ds");
        run1.setChannelName("DeepSeek");
        run1.setModel("deepseek-chat");
        run1.setPassed(95);
        run1.setFailed(5);
        run1.setTotal(100);
        run1.setSummary("{\"passRate\":95.0,\"byCategory\":{\"权限与安全\":{\"passed\":10,\"failed\":0},\"数据查询\":{\"passed\":8,\"failed\":2}}}");
        when(runRepository.findTop20ByOrderByStartedAtDesc()).thenReturn(List.of(run1));

        List<Map<String, Object>> cmp = service.channelComparison();
        assertEquals(1, cmp.size());
        assertEquals("DeepSeek", cmp.get(0).get("channelName"));
        assertTrue(String.valueOf(cmp.get(0).get("belowThresholdCategories")).contains("数据查询"));

        // 2. 待复盘队列 + 转评测用例草稿
        com.shimeta.model.AssistantMessage uMsg = new com.shimeta.model.AssistantMessage();
        uMsg.setId("asm-u1");
        uMsg.setConversationId("asc-1");
        uMsg.setRole("user");
        uMsg.setContent("知识库有几个文档？");
        com.shimeta.model.AssistantMessage aMsg = new com.shimeta.model.AssistantMessage();
        aMsg.setId("asm-a1");
        aMsg.setConversationId("asc-1");
        aMsg.setRole("assistant");
        aMsg.setContent("大概有 99 篇文档。");
        aMsg.setFeedback("DOWN");
        aMsg.setVerificationFlags("[\"UNGROUNDED_DATA\"]");
        aMsg.setToolCalls("[{\"name\":\"list_knowledge_bases\",\"ok\":false}]");
        when(msgRepo.findReviewCandidates(50)).thenReturn(List.of(aMsg));
        when(msgRepo.findByConversationIdOrderByCreatedAtAsc("asc-1")).thenReturn(List.of(uMsg, aMsg));
        when(msgRepo.findById("asm-a1")).thenReturn(java.util.Optional.of(aMsg));

        List<Map<String, Object>> queue = service.reviewQueue(50);
        assertEquals(1, queue.size());
        assertEquals("知识库有几个文档？", queue.get(0).get("question"));
        assertTrue(String.valueOf(queue.get(0).get("reasons")).contains("USER_DOWN"));
        assertTrue(String.valueOf(queue.get(0).get("reasons")).contains("TOOL_FAILED"));
        assertTrue(String.valueOf(queue.get(0).get("reasons")).contains("VERIFICATION_FLAG"));

        Map<String, Object> draft = service.draftCaseFromReview("asm-a1");
        assertEquals("知识库有几个文档？", draft.get("question"));

        // 3. 4.8 模型规模分级门控：Ollama 默认 <7B 禁用执行模式，升级到 7B+ 且支持工具后自动开启
        CurrentActor admin = new CurrentActor("u-1", "admin", UserRole.SUPER_ADMIN);
        com.shimeta.model.LlmProvider ollama = new com.shimeta.model.LlmProvider();
        ollama.setId("prov-ol");
        ollama.setName("本地 Ollama");
        ollama.setProtocol(com.shimeta.model.LlmProtocolType.OLLAMA);
        ollama.setSupportsTools(true);
        ollama.setDefaultModel("qwen3:1.7b");
        when(gw.resolveAssistantRoute()).thenReturn(java.util.Optional.of(
                new com.shimeta.service.LlmGatewayService.AssistantRoute(
                        new com.shimeta.service.LlmGatewayService.ResolvedRoute(ollama, null, 30000, 0, "", null), null, false)));

        Map<String, Object> capSmall = service.assistantCapabilities(admin);
        assertEquals(false, capSmall.get("agentModeEnabled"));
        assertTrue(String.valueOf(capSmall.get("reason")).contains("7B 以上"));

        ollama.setModelSizeTier(com.shimeta.service.LlmGatewayService.TIER_7B_PLUS);
        ollama.setDefaultModel("qwen3.5:9b");
        Map<String, Object> capLarge = service.assistantCapabilities(admin);
        assertEquals(true, capLarge.get("agentModeEnabled"));
    }
}
