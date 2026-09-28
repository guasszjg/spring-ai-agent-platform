package com.example.agentplatform.assistant;

import com.example.agentplatform.model.AssistantAction;
import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.repository.AssistantActionRepository;
import com.example.agentplatform.repository.AssistantConversationRepository;
import com.example.agentplatform.repository.AssistantEvalRunRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.AgentService;
import com.example.agentplatform.service.KnowledgeBaseService;
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
    void bundledCasesLoadAndOnlyReferenceExistingTools() {
        Set<String> known = new HashSet<>(AssistantEvalScorer.WRITE_TOOLS);
        known.addAll(List.of("list_agents", "get_agent_detail", "list_knowledge_bases", "get_gateway_status",
                "get_usage_summary", "diagnose_agent", "list_templates", "search_platform_docs"));
        List<AssistantEvalCase> cases = service.cases();

        assertTrue(cases.size() >= 50, "评测用例数量应不少于 50，实际 " + cases.size());
        Set<String> ids = new HashSet<>();
        for (AssistantEvalCase c : cases) {
            assertTrue(ids.add(c.id()), "用例 ID 重复：" + c.id());
            assertTrue(c.question() != null && !c.question().isBlank(), c.id() + " 缺少问题");
            assertTrue(c.expect() != null, c.id() + " 缺少期望");
            var e = c.expect();
            for (List<String> tools : java.util.Arrays.asList(e.toolsAll(), e.toolsAny(), e.toolsNone())) {
                for (String tool : AssistantEvalScorer.expand(tools)) {
                    assertTrue(known.contains(tool), c.id() + " 引用了不存在的工具：" + tool);
                }
            }
            if (e.args() != null) {
                e.args().forEach(a -> {
                    assertTrue(known.contains(a.tool()), c.id() + " 引用了不存在的工具：" + a.tool());
                    Pattern.compile(AssistantEvalService.substitute(a.pattern(), Map.of("agent", "x", "kb", "y"), true));
                });
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
        var c = new AssistantEvalCase("x", "数据查询", "SUPER_ADMIN", null, null, "「{{kb}}」有几篇文档？",
                new AssistantEvalCase.Expect(null, null, null, null, null, null, null, null, null, null, null));

        Map<String, Object> result = service.runCase(c, Map.of("year", "2026"));

        assertEquals("SKIPPED", result.get("status"));
    }

    @Test
    void runsCaseAsVirtualRoleScoresAndCancelsGeneratedCards() {
        var c = new AssistantEvalCase("w", "创建修改", "SUPER_ADMIN", null, null, "帮我建一个客服智能体",
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
        var c = new AssistantEvalCase("q", "数据查询", "VIEWER", null, null, "有哪些智能体",
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
}
