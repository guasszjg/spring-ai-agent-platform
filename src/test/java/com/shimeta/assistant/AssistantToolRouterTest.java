package com.shimeta.assistant;

import com.shimeta.model.EmbeddingConfig;
import com.shimeta.model.UserRole;
import com.shimeta.rag.pipeline.LocalEmbeddingService;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.EmbeddingConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class AssistantToolRouterTest {

    private final CurrentActor admin = new CurrentActor("u1", "admin", UserRole.SUPER_ADMIN);
    private final CurrentActor dev = new CurrentActor("u2", "dev", UserRole.DEVELOPER);

    @Test
    void keywordRoutingCoversAllGroupsAndFiltersSuperAdminOnlyForNonAdmin() {
        AssistantToolRouter router = new AssistantToolRouter();

        var skills = router.route("技能库里有哪些技能？", null, null, dev, false);
        assertEquals(Set.of("skills"), skills.groupIds());
        assertEquals(Set.of("list_skills"), skills.allowedReadTools());
        assertTrue(skills.hitsReadDataGroup());

        var usage = router.route("最近哪个模型最费钱？花费排行是什么", null, null, dev, false);
        assertTrue(usage.groupIds().contains("usage"));
        assertTrue(usage.allowedReadTools().contains("get_usage_summary"));
        assertTrue(usage.hitsReadDataGroup());

        var security = router.route("看看我的开放凭证和护栏策略", null, null, dev, false);
        assertTrue(security.groupIds().contains("security"));
        assertTrue(security.allowedReadTools().contains("list_resources"));
        assertTrue(security.allowedReadTools().contains("get_resource"));
        assertTrue(security.hitsReadDataGroup());

        var adminUsers = router.route("平台有哪些用户列表？", null, null, admin, false);
        assertTrue(adminUsers.groupIds().contains("users_roles"));

        var devUsers = router.route("平台有哪些用户列表？", null, null, dev, false);
        assertFalse(devUsers.groupIds().contains("users_roles"), "非超管不得命中 users_roles 分组");
    }

    @Test
    void pureHowToQuestionAndChitchatDoNotTriggerReadDataForceRetry() {
        AssistantToolRouter router = new AssistantToolRouter();

        var chitchat = router.route("你好！", null, null, dev, false);
        assertFalse(chitchat.hitsReadDataGroup());
        assertEquals("chitchat", chitchat.strategy());

        var pingOnAgentsPage = router.route("在吗?", Map.of("page", "agents"), null, admin, false);
        assertFalse(pingOnAgentsPage.hitsReadDataGroup(), "在智能体页寒暄「在吗?」不得触发强制调工具");
        assertEquals("chitchat", pingOnAgentsPage.strategy());

        var generalChatOnAgentsPage = router.route("帮我讲个冷笑话", Map.of("page", "agents"), null, admin, false);
        assertFalse(generalChatOnAgentsPage.hitsReadDataGroup(), "仅因页面上下文补入工具组时不得触发 tool_choice=required");

        var howTo = router.route("先检索后回答和模型自主检索有什么区别？", null, null, dev, false);
        assertFalse(howTo.hitsReadDataGroup(), "纯概念/使用文档问题不应触发查询强制重试");
        assertTrue(howTo.allowedReadTools() == null || howTo.allowedReadTools().contains("search_platform_docs"));
    }

    @Test
    void writeIntentNarrowsWriteToolsToMatchedGroup() {
        AssistantToolRouter router = new AssistantToolRouter();

        var agentWrite = router.route("帮我把客服智能体的提示词改得更专业", null, null, dev, true);
        assertTrue(agentWrite.groupIds().contains("agents"));
        assertNotNull(agentWrite.allowedWriteTools());
        assertTrue(agentWrite.allowedWriteTools().contains("update_agent_prompt"));
        assertFalse(agentWrite.allowedWriteTools().contains("create_api_key"));
        assertFalse(agentWrite.allowedWriteTools().contains("add_faq"));

        var secWrite = router.route("帮我签发一个开放凭证 API Key", null, null, dev, true);
        assertNotNull(secWrite.allowedWriteTools());
        assertTrue(secWrite.allowedWriteTools().contains("create_api_key"));
        assertFalse(secWrite.allowedWriteTools().contains("create_knowledge_base"));
    }

    @Test
    void multiTurnFollowUpInheritsPreviousGroupsAndPageContext() {
        AssistantToolRouter router = new AssistantToolRouter();

        var followUp = router.route("再详细看看它的配置", null, Set.of("knowledge"), dev, false);
        assertTrue(followUp.groupIds().contains("knowledge"), "短追问应继承上一轮工具组");

        var withPage = router.route("这个怎么样", Map.of("page", "debug", "resourceType", "AGENT", "resourceId", "ag-1"),
                null, dev, false);
        assertTrue(withPage.groupIds().contains("agents"), "页面上下文应自动补入对应工具组");
    }

    @Test
    void vectorSimilarityRoutingMatchesColloquialParaphrasesAndFallsBackOnError() {
        EmbeddingConfigService cfgSvc = Mockito.mock(EmbeddingConfigService.class);
        LocalEmbeddingService embSvc = Mockito.mock(LocalEmbeddingService.class);
        EmbeddingConfig cfg = new EmbeddingConfig();
        cfg.setId("emb-1");
        cfg.setModelName("bge-m3");
        when(cfgSvc.getActiveConfig()).thenReturn(Optional.of(cfg));

        // 构造简单正交向量：usage 维度 0，agents 维度 1，其余维度 2
        when(embSvc.embed(anyString())).thenAnswer(inv -> {
            String t = inv.getArgument(0);
            if (t.contains("花钱") || t.contains("花费") || t.contains("token") || t.contains("账单")) {
                return new float[]{1f, 0f, 0f};
            }
            if (t.contains("机器人") || t.contains("智能体") || t.contains("机器员工")) {
                return new float[]{0f, 1f, 0f};
            }
            return new float[]{0f, 0f, 1f};
        });

        AssistantToolRouter router = new AssistantToolRouter(cfgSvc, embSvc);
        // "查一下账单" 不含任何关键词（不含 token/成本/用量/花费），纯靠向量相似度命中 usage 组
        var res = router.route("查一下账单情况", null, null, dev, false);
        assertTrue(res.groupIds().contains("usage"), "口语问法应通过向量相似度命中 usage 组，实际: " + res.groupIds());
        assertEquals("vector", res.strategy());
        assertTrue(res.allowedReadTools().contains("get_usage_summary"));

        // 向量服务异常时应平滑退回关键词路由且不抛错
        router.clearCache();
        when(embSvc.embed(anyString())).thenThrow(new IllegalStateException("connect timed out"));
        var fallback = router.route("平台有哪些 MCP 服务？", null, null, dev, false);
        assertEquals("keyword", fallback.strategy());
        assertTrue(fallback.groupIds().contains("tools_mcp"));
        assertNotNull(fallback.allowedReadTools());
        assertTrue(fallback.allowedReadTools().contains("list_mcp_servers"));
    }

    @Test
    void toolRegistryNarrowsBothReadAndWriteTools() {
        AssistantToolRegistry registry = new AssistantToolRegistry(java.util.List.of(
                AssistantTool.read("list_agents", "查智能体", "desc", Map.of(), (a, c) -> Map.of()),
                AssistantTool.read("list_skills", "查技能", "desc", Map.of(), (a, c) -> Map.of()),
                AssistantTool.write("update_agent_prompt", "改提示词", "desc", Map.of(), (a, c) -> Map.of()),
                AssistantTool.write("create_api_key", "签发凭证", "desc", Map.of(), (a, c) -> Map.of())
        ));
        var defs = registry.definitions(dev, true, Set.of("list_agents"), Set.of("update_agent_prompt"));
        String json = defs.toString();
        assertTrue(json.contains("list_agents"));
        assertTrue(json.contains("update_agent_prompt"));
        assertFalse(json.contains("list_skills"));
        assertFalse(json.contains("create_api_key"));

        // 无交集时保留兜底，不会把写工具清空
        var fallback = registry.definitions(dev, true, Set.of("non_existent_read"), Set.of("non_existent_write"));
        assertEquals(4, fallback.size());
        assertNull(AssistantToolRouter.ToolSelection.ALL.allowedReadTools());
    }
}
