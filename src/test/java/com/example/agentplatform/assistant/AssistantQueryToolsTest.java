package com.example.agentplatform.assistant;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.model.GatewayOverview;
import com.example.agentplatform.model.GatewayPolicy;
import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.LlmProvider;
import com.example.agentplatform.model.LlmProviderView;
import com.example.agentplatform.model.PageResult;
import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.rag.dto.KnowledgeEngineInfo;
import com.example.agentplatform.repository.AgentDailyStatRepository;
import com.example.agentplatform.repository.KnowledgeBaseRepository;
import com.example.agentplatform.repository.KnowledgeIndexVersionRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.AgentService;
import com.example.agentplatform.service.AgentTemplateService;
import com.example.agentplatform.service.DifyConfigService;
import com.example.agentplatform.service.EmbeddingConfigService;
import com.example.agentplatform.service.KnowledgeBaseService;
import com.example.agentplatform.service.LlmGatewayService;
import com.example.agentplatform.service.ResourceAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssistantQueryToolsTest {

    @Mock AgentService agentService;
    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock KnowledgeIndexVersionRepository indexVersionRepository;
    @Mock LlmGatewayService gatewayService;
    @Mock EmbeddingConfigService embeddingConfigService;
    @Mock DifyConfigService difyConfigService;
    @Mock ResourceAuthorizationService authorizationService;
    @Mock AgentDailyStatRepository dailyStatRepository;
    @Mock AgentTemplateService templateService;
    @Mock PlatformDocsService platformDocsService;

    private final ObjectMapper mapper = new ObjectMapper();
    private final CurrentActor developer = new CurrentActor("u-dev", "dev", UserRole.DEVELOPER);
    private final CurrentActor admin = new CurrentActor("u-admin", "admin", UserRole.SUPER_ADMIN);
    private AssistantQueryTools tools;

    @BeforeEach
    void setUp() {
        tools = new AssistantQueryTools(agentService, knowledgeBaseService, knowledgeBaseRepository, indexVersionRepository,
                gatewayService, embeddingConfigService, difyConfigService, authorizationService, dailyStatRepository,
                templateService, platformDocsService);
        when(dailyStatRepository.findByAgentIdAndStatDateBetween(any(), any(), any())).thenReturn(List.of());
        when(authorizationService.canRunAgent(any(), any())).thenReturn(true);
    }

    private static Agent agent(String id, String name) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setName(name);
        agent.setStatus(AgentStatus.RUNNING);
        agent.setModelName("deepseek-v4-flash");
        agent.setApiKey("sk-agent-secret-should-never-leak");
        agent.setSystemPrompt("你是售后客服");
        return agent;
    }

    private String run(String tool, String args, CurrentActor actor) {
        return new AssistantToolRegistry(tools.tools()).execute(tool, args, ToolContext.of(actor, "asc-test")).content();
    }

    @Test
    void listAgentsQueriesAsCurrentUserCapsLimitAndNeverReturnsApiKey() {
        when(agentService.searchAgents(eq("客服"), isNull(), eq(AgentStatus.RUNNING), isNull(), eq(developer), eq(1), eq(20)))
                .thenReturn(new PageResult<>(List.of(agent("a1", "售后客服")), 1, 1, 20));

        String json = run("list_agents", "{\"keyword\":\"客服\",\"status\":\"RUNNING\",\"limit\":500}", developer);

        verify(agentService).searchAgents(eq("客服"), isNull(), eq(AgentStatus.RUNNING), isNull(), eq(developer), eq(1), eq(20));
        assertTrue(json.contains("售后客服"));
        assertFalse(json.contains("sk-agent-secret"));
        assertFalse(json.toLowerCase().contains("apikey"));
    }

    @Test
    void gatewayStatusHidesChannelDetailsFromNonAdmins() throws Exception {
        when(gatewayService.activeRoute()).thenReturn(Map.of("channel", "DeepSeek", "model", "deepseek-v4-flash"));

        var node = mapper.readTree(run("get_gateway_status", "{}", developer));

        assertTrue(node.path("available").asBoolean());
        assertEquals("deepseek-v4-flash", node.path("defaultModel").asText());
        assertTrue(node.path("channels").isMissingNode());
        assertFalse(node.toString().contains("DeepSeek\""));
    }

    @Test
    void gatewayStatusForAdminListsChannelsWithoutSecretsOrAddresses() throws Exception {
        when(gatewayService.activeRoute()).thenReturn(Map.of("channel", "DeepSeek", "model", "deepseek-v4-flash"));
        LlmProviderView view = new LlmProviderView();
        view.setId("llm-deepseek");
        view.setName("DeepSeek");
        view.setBaseUrl("https://internal-proxy.example/v1");
        view.setApiKeyMasked("sk-****abcd");
        view.setCustomConfig("{\"headers\":{\"X-Token\":\"header-secret\"}}");
        view.setEnabled(true);
        view.setConfigured(true);
        view.setLastProbeStatus("SUCCESS");
        GatewayPolicy policy = new GatewayPolicy();
        policy.setDefaultProviderId("llm-deepseek");
        GatewayOverview overview = new GatewayOverview();
        overview.setProviders(List.of(view));
        overview.setPolicy(policy);
        when(gatewayService.overview()).thenReturn(overview);
        when(embeddingConfigService.getActiveConfig()).thenReturn(Optional.empty());
        when(knowledgeBaseService.getEngineInfo()).thenReturn(new KnowledgeEngineInfo());
        when(difyConfigService.applyReadiness(any())).thenAnswer(inv -> inv.getArgument(0));

        String json = run("get_gateway_status", "{}", admin);
        var node = mapper.readTree(json);

        assertEquals("默认通道", node.path("channels").path(0).path("role").asText());
        assertFalse(json.contains("internal-proxy"));
        assertFalse(json.contains("sk-****"));
        assertFalse(json.contains("header-secret"));
    }

    @Test
    void ambiguousAgentReferenceReturnsCandidatesInsteadOfGuessing() throws Exception {
        when(agentService.getById(eq("客服"), eq(developer))).thenReturn(Optional.empty());
        when(agentService.searchAgents(eq("客服"), isNull(), isNull(), isNull(), eq(developer), eq(1), anyInt()))
                .thenReturn(new PageResult<>(List.of(agent("a1", "售后客服"), agent("a2", "售前客服")), 2, 1, 50));

        var node = mapper.readTree(run("get_agent_detail", "{\"agent\":\"客服\"}", developer));

        assertFalse(node.path("ok").asBoolean());
        assertTrue(node.path("ambiguous").asBoolean());
        assertEquals(2, node.path("candidates").size());
    }

    @Test
    void agentWithoutPermissionIsReportedAsNotFound() throws Exception {
        when(agentService.getById(eq("a-private"), eq(developer))).thenThrow(new IllegalStateException("权限不足：无权访问该智能体"));
        when(agentService.searchAgents(eq("a-private"), isNull(), isNull(), isNull(), eq(developer), eq(1), anyInt()))
                .thenReturn(new PageResult<>(List.of(), 0, 1, 50));

        var node = mapper.readTree(run("diagnose_agent", "{\"agent\":\"a-private\"}", developer));

        assertFalse(node.path("ok").asBoolean());
        assertTrue(node.path("error").asText().contains("没有找到"));
    }

    @Test
    void diagnoseFlagsDisabledAgentAndMissingChannel() throws Exception {
        Agent disabled = agent("a1", "售后客服");
        disabled.setStatus(AgentStatus.DISABLED);
        when(agentService.getById(eq("a1"), eq(developer))).thenReturn(Optional.of(disabled));
        when(gatewayService.resolveRoute(any())).thenReturn(Optional.empty());

        var node = mapper.readTree(run("diagnose_agent", "{\"agent\":\"a1\"}", developer));

        assertTrue(node.path("ok").asBoolean());
        assertTrue(node.path("conclusion").asText().contains("2 个"));
        var checks = node.path("checks");
        assertEquals("FAIL", checks.path(0).path("status").asText());
        assertTrue(checks.toString().contains("没有可用的大模型通道"));
        assertTrue(checks.toString().contains("联系超级管理员"));
    }

    @Test
    void diagnoseHidesNamesOfKnowledgeBasesTheUserCannotView() throws Exception {
        Agent a = agent("a1", "售后客服");
        a.setKnowledgeBaseIds(List.of("kb-secret"));
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-secret");
        kb.setName("薪资制度（机密）");
        when(agentService.getById(eq("a1"), eq(developer))).thenReturn(Optional.of(a));
        when(knowledgeBaseRepository.findById("kb-secret")).thenReturn(Optional.of(kb));
        when(authorizationService.canViewKnowledgeBase(developer, kb)).thenReturn(false);
        LlmProvider provider = new LlmProvider();
        provider.setName("DeepSeek");
        provider.setDefaultModel("deepseek-v4-flash");
        when(gatewayService.resolveRoute(any())).thenReturn(Optional.of(
                new LlmGatewayService.ResolvedRoute(provider, null, 30000, 1, "k", null)));

        String diagnose = run("diagnose_agent", "{\"agent\":\"a1\"}", developer);
        String detail = run("get_agent_detail", "{\"agent\":\"a1\"}", developer);

        assertFalse(diagnose.contains("薪资制度"));
        assertFalse(detail.contains("薪资制度"));
        assertTrue(detail.contains("无权查看"));
    }

    @Test
    void effectiveModelFollowsGatewayRule() {
        LlmProvider provider = new LlmProvider();
        provider.setModels("deepseek-v4-flash, deepseek-v4-pro");
        provider.setDefaultModel("deepseek-v4-flash");

        assertEquals("deepseek-v4-pro", AssistantQueryTools.effectiveModel(provider, "deepseek-v4-pro"));
        assertEquals("deepseek-v4-flash", AssistantQueryTools.effectiveModel(provider, "gpt-4o"));
        assertEquals("deepseek-v4-flash", AssistantQueryTools.effectiveModel(provider, null));
    }

    @Test
    void enabledToolNamesSkipDisabledTools() {
        String config = "[{\"name\":\"时区转换\",\"title\":\"时区转换\",\"enabled\":true},"
                + "{\"name\":\"联网检索\",\"enabled\":false},{\"name\":\"时间戳转换\"}]";
        assertEquals(List.of("时区转换", "时间戳转换"), tools.enabledToolNames(config));
        assertEquals(List.of(), tools.enabledToolNames("not json"));
    }

    // ==================== P3 ====================

    @Test
    void testGatewayChannelProbesMatchedChannelForAdminOnly() throws Exception {
        LlmProviderView view = new LlmProviderView();
        view.setId("llm-deepseek");
        view.setName("DeepSeek");
        view.setEnabled(true);
        view.setConfigured(true);
        GatewayOverview overview = new GatewayOverview();
        overview.setProviders(List.of(view));
        when(gatewayService.overview()).thenReturn(overview);
        LlmProviderView probed = new LlmProviderView();
        probed.setId("llm-deepseek");
        probed.setName("DeepSeek");
        probed.setLastProbeStatus("SUCCESS");
        probed.setLastProbeMessage("ok");
        when(gatewayService.probe("llm-deepseek")).thenReturn(probed);

        var node = mapper.readTree(run("test_gateway_channel", "{\"channel\":\"deepseek\"}", admin));
        assertEquals("SUCCESS", node.path("status").asText());
        verify(gatewayService).probe("llm-deepseek");

        // 开发者看不到也调用不了
        assertTrue(new AssistantToolRegistry(tools.tools()).definitions(developer).stream()
                .noneMatch(d -> String.valueOf(d).contains("test_gateway_channel")));
        assertFalse(mapper.readTree(run("test_gateway_channel", "{\"channel\":\"deepseek\"}", developer)).path("ok").asBoolean(true));
        verify(gatewayService, org.mockito.Mockito.times(1)).probe(any());
    }

    @Test
    void describeContextExpandsOnlyVisibleResources() {
        when(agentService.getById(eq("a1"), eq(developer))).thenReturn(Optional.of(agent("a1", "售后客服")));
        String described = tools.describeContext(Map.of("page", "debug", "resourceType", "AGENT", "resourceId", "a1"), developer);
        assertTrue(described.contains("智能体编排与调试"));
        assertTrue(described.contains("售后客服"));
        assertTrue(described.contains("a1"));

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-secret");
        kb.setName("机密库");
        when(knowledgeBaseRepository.findById("kb-secret")).thenReturn(Optional.of(kb));
        when(authorizationService.canViewKnowledgeBase(developer, kb)).thenReturn(false);
        String hidden = tools.describeContext(Map.of("page", "knowledge", "resourceType", "KNOWLEDGE_BASE", "resourceId", "kb-secret"), developer);
        assertFalse(hidden.contains("机密库"));
        assertFalse(hidden.contains("kb-secret"));

        when(agentService.getById(eq("a-private"), eq(developer))).thenThrow(new IllegalStateException("权限不足"));
        assertEquals(null, tools.describeContext(Map.of("resourceType", "AGENT", "resourceId", "a-private"), developer));
        assertEquals(null, tools.describeContext(Map.of(), developer));
    }

    @Test
    void resourceLinksPointToInternalPages() {
        assertEquals("/debug/a1", AssistantQueryTools.agentLink("a1"));
        assertEquals("/dashboard?tab=knowledge&kb=kb-1", AssistantQueryTools.knowledgeBaseLink("kb-1"));
    }
}
