package com.shimeta.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.mcp.McpServerService;
import com.shimeta.mcp.McpServerView;
import com.shimeta.model.Agent;
import com.shimeta.model.AgentStatus;
import com.shimeta.model.AgentTemplate;
import com.shimeta.model.AssistantAction;
import com.shimeta.model.EmbeddingConfig;
import com.shimeta.model.GatewayOverview;
import com.shimeta.model.GatewayPolicy;
import com.shimeta.model.GuardrailPolicy;
import com.shimeta.model.KnowledgeBase;
import com.shimeta.model.KnowledgeFaq;
import com.shimeta.model.LlmProviderRequest;
import com.shimeta.model.LlmProviderView;
import com.shimeta.model.OpenApiKey;
import com.shimeta.model.PageResult;
import com.shimeta.model.PlatformToolView;
import com.shimeta.model.UserRole;
import com.shimeta.model.UserSummaryDto;
import com.shimeta.repository.AssistantActionRepository;
import com.shimeta.repository.KnowledgeBaseRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.AgentPromptVersionService;
import com.shimeta.service.AgentService;
import com.shimeta.service.AgentTemplateService;
import com.shimeta.service.AuditRecorder;
import com.shimeta.service.DifyConfigService;
import com.shimeta.service.EmbeddingConfigService;
import com.shimeta.service.KnowledgeBaseService;
import com.shimeta.service.KnowledgeIndexRebuildService;
import com.shimeta.service.LlmGatewayService;
import com.shimeta.service.OpenApiKeyService;
import com.shimeta.service.PlatformToolService;
import com.shimeta.service.RagEvaluationService;
import com.shimeta.service.RerankConfigService;
import com.shimeta.service.ResourceAuthorizationService;
import com.shimeta.service.SecurityPlatformService;
import com.shimeta.service.UserAdminService;
import com.shimeta.skill.SkillService;
import com.shimeta.skill.SkillView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssistantExtendedWriteToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CurrentActor admin = new CurrentActor("u-admin", "admin", UserRole.SUPER_ADMIN);
    private final CurrentActor dev = new CurrentActor("u-dev", "dev", UserRole.DEVELOPER);

    @Mock AgentService agentService;
    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock LlmGatewayService gatewayService;
    @Mock EmbeddingConfigService embeddingConfigService;
    @Mock DifyConfigService difyConfigService;
    @Mock ResourceAuthorizationService authorizationService;
    @Mock AgentTemplateService templateService;
    @Mock OpenApiKeyService openApiKeyService;
    @Mock AssistantActionRepository actionRepository;
    @Mock PlatformToolService platformToolService;
    @Mock SkillService skillService;
    @Mock McpServerService mcpServerService;
    @Mock AgentPromptVersionService promptVersionService;
    @Mock KnowledgeIndexRebuildService rebuildService;
    @Mock RagEvaluationService ragEvaluationService;
    @Mock UserAdminService userAdminService;
    @Mock SecurityPlatformService securityPlatformService;
    @Mock RerankConfigService rerankConfigService;
    @Mock AuditRecorder auditRecorder;

    private AssistantQueryTools queryTools;
    private AssistantWriteTools baseWriteTools;
    private AssistantExtendedWriteTools extendedWriteTools;
    private AssistantActionService actionService;

    @BeforeEach
    void setUp() {
        queryTools = new AssistantQueryTools(agentService, knowledgeBaseService, knowledgeBaseRepository,
                null, gatewayService, embeddingConfigService, difyConfigService,
                authorizationService, null, templateService, null);
        baseWriteTools = new AssistantWriteTools(queryTools, agentService, templateService, knowledgeBaseService,
                knowledgeBaseRepository, embeddingConfigService, authorizationService, actionRepository, openApiKeyService);
        extendedWriteTools = new AssistantExtendedWriteTools(queryTools, agentService, templateService,
                knowledgeBaseService, knowledgeBaseRepository, authorizationService, gatewayService,
                embeddingConfigService, openApiKeyService, actionRepository);
        extendedWriteTools.setPlatformToolService(platformToolService);
        extendedWriteTools.setSkillService(skillService);
        extendedWriteTools.setMcpServerService(mcpServerService);
        extendedWriteTools.setPromptVersionService(promptVersionService);
        extendedWriteTools.setRebuildService(rebuildService);
        extendedWriteTools.setRagEvaluationService(ragEvaluationService);
        extendedWriteTools.setUserAdminService(userAdminService);
        extendedWriteTools.setSecurityPlatformService(securityPlatformService);
        extendedWriteTools.setRerankConfigService(rerankConfigService);
        extendedWriteTools.setDifyConfigService(difyConfigService);

        actionService = new AssistantActionService(actionRepository, baseWriteTools, auditRecorder);
        actionService.setExtendedWriteTools(extendedWriteTools);

        when(actionRepository.save(any(AssistantAction.class))).thenAnswer(inv -> {
            AssistantAction a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId("act-1234567890ab");
            }
            if (a.getCreatedAt() == null) {
                a.setCreatedAt(LocalDateTime.now());
            }
            return a;
        });
    }

    @Test
    void levelBUpdateAgentSettingsGeneratesMultiDiffEnforcesOptimisticLockAndReadsBack() throws Exception {
        Agent agent = new Agent();
        agent.setId("ag-1");
        agent.setName("售后客服");
        agent.setTemperature(0.7);
        agent.setMemoryTurns(0);
        agent.setThinkingMode("OFF");
        agent.setKnowledgeMode("PRE_RETRIEVE");
        when(agentService.getById("ag-1", dev)).thenReturn(Optional.of(agent));
        when(authorizationService.canManageAgent(dev, agent)).thenReturn(true);

        ToolContext ctx = ToolContext.of(dev, "asc-1");
        Map<String, Object> draft = extendedWriteTools.draftUpdateAgentSettings(
                mapper.readTree("{\"agent\":\"ag-1\",\"temperature\":0.3,\"memoryTurns\":3,\"thinkingMode\":\"ON\"}"), ctx);
        assertTrue((Boolean) draft.get("ok"));
        assertEquals("B", draft.get("riskLevel"));
        AssistantAction action = ctx.createdActions().get(0);
        assertTrue(action.getPreview().contains("\"diffs\""));
        assertTrue(action.getPreview().contains("0.3"));
        assertTrue(action.getPreview().contains("3 轮"));

        // 并发修改导致基线失配时应拒绝执行，防止静默覆盖
        agent.setTemperature(0.9);
        assertThrows(IllegalStateException.class,
                () -> extendedWriteTools.execute(AssistantExtendedWriteTools.UPDATE_AGENT_SETTINGS, action.getPayload(), null, dev));

        // 恢复原值并正常确认执行，应带回读摘要
        agent.setTemperature(0.7);
        Agent updated = new Agent();
        updated.setId("ag-1");
        updated.setName("售后客服");
        updated.setTemperature(0.3);
        updated.setMemoryTurns(3);
        updated.setThinkingMode("ON");
        updated.setKnowledgeMode("PRE_RETRIEVE");
        when(agentService.update(eq("ag-1"), any(Agent.class), eq(dev))).thenReturn(updated);
        when(agentService.getById("ag-1", dev)).thenReturn(Optional.of(agent), Optional.of(updated));

        AssistantWriteTools.ExecutionResult res = extendedWriteTools.execute(
                AssistantExtendedWriteTools.UPDATE_AGENT_SETTINGS, action.getPayload(), null, dev);
        assertNotNull(res.readback());
        assertTrue(res.readback().contains("温度=0.3"));
        assertTrue(res.readback().contains("记忆=3轮"));
    }

    @Test
    void levelDRulesEnforceFiveMinuteTtlMaxOnePerTurnNoChainingAndImpactPreview() throws Exception {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-1");
        kb.setName("核心产品库");
        kb.setProvider("SPRING_AI");
        kb.setDocumentCount(12);
        kb.setFaqCount(5);
        when(knowledgeBaseRepository.findById("kb-1")).thenReturn(Optional.of(kb));
        when(authorizationService.canViewKnowledgeBase(dev, kb)).thenReturn(true);
        when(authorizationService.canManageKnowledgeBase(dev, kb)).thenReturn(true);

        Agent bound = new Agent();
        bound.setId("ag-9");
        bound.setName("产品顾问");
        bound.setKnowledgeBaseIds(List.of("kb-1"));
        when(agentService.searchAgents(isNull(), isNull(), isNull(), isNull(), eq(dev), eq(1), eq(500)))
                .thenReturn(new PageResult<>(List.of(bound), 1, 1, 500));

        // 禁止用 pendingActionId 串联高危删除
        ToolContext ctx = ToolContext.of(dev, "asc-1");
        assertThrows(IllegalArgumentException.class, () -> extendedWriteTools.draftDeleteResource(
                mapper.readTree("{\"type\":\"knowledge_base\",\"id\":\"act-abcdef1234567890\"}"), ctx));

        // 正常生成第 1 张 D 级卡片：5 分钟过期、含影响范围和目标名称
        Map<String, Object> draft = extendedWriteTools.draftDeleteResource(
                mapper.readTree("{\"type\":\"knowledge_base\",\"id\":\"kb-1\"}"), ctx);
        assertTrue((Boolean) draft.get("ok"));
        assertEquals("D", draft.get("riskLevel"));
        assertEquals("核心产品库", draft.get("confirmTargetName"));
        AssistantAction action = ctx.createdActions().get(0);
        long minutes = Duration.between(LocalDateTime.now().minusSeconds(5), action.getExpiresAt()).toMinutes();
        assertTrue(minutes >= 4 && minutes <= 5, "D 级卡片 TTL 应为 5 分钟，实际 " + minutes);
        assertTrue(action.getPreview().contains("产品顾问"), "删除知识库卡片必须列出受影响的绑定智能体");

        // 同一轮对话再生成第 2 张 D 级卡片必须被拒绝
        assertThrows(IllegalArgumentException.class, () -> extendedWriteTools.draftDeleteResource(
                mapper.readTree("{\"type\":\"knowledge_base\",\"id\":\"kb-1\"}"), ctx));
    }

    @Test
    void levelDConfirmRejectsWrongTargetNameWithoutConsumingCardAndAuditsHighRiskOnMatch() throws Exception {
        OpenApiKey key = new OpenApiKey();
        key.setId("key-1");
        key.setName("生产客服凭证");
        key.setKeyPrefix("sk-live-aabbccdd");
        key.setStatus("ACTIVE");
        when(openApiKeyService.listForActor(dev)).thenReturn(List.of(key));

        ToolContext ctx = ToolContext.of(dev, "asc-1");
        extendedWriteTools.draftRevokeApiKey(
                mapper.readTree("{\"key\":\"key-1\",\"operation\":\"revoke\"}"), ctx);
        AssistantAction action = ctx.createdActions().get(0);
        action.setStatus(AssistantAction.PENDING);

        when(actionRepository.findByIdAndUserId(action.getId(), dev.getUserId())).thenReturn(Optional.of(action));
        when(actionRepository.claim(eq(action.getId()), eq(dev.getUserId()), any())).thenReturn(1);

        // 1. 名称输入错误：抛 ConfirmationMismatchException 且绝不消耗卡片（不调用 claim）
        assertThrows(AssistantActionService.ConfirmationMismatchException.class,
                () -> actionService.confirm(action.getId(), new AssistantActionConfirmRequest("写错的名字", null), dev));
        verify(actionRepository, never()).claim(anyString(), anyString(), any());
        verify(openApiKeyService, never()).delete(anyString(), any());
        assertEquals(AssistantAction.PENDING, action.getStatus());

        // 2. 名称完全一致：执行吊销并记录 HIGH 级审计（含 via=assistant 与 targetName/impacts）
        Map<String, Object> card = actionService.confirm(action.getId(),
                new AssistantActionConfirmRequest("生产客服凭证", null), dev);
        assertEquals(AssistantAction.EXECUTED, card.get("status"));
        verify(openApiKeyService).delete("key-1", dev);

        ArgumentCaptor<String> riskCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detailCap = ArgumentCaptor.forClass(String.class);
        verify(auditRecorder).record(eq("assistant.action.revoke_api_key"), eq("API_KEY"), eq("key-1"),
                eq("SUCCESS"), isNull(), riskCap.capture(), detailCap.capture());
        assertEquals("HIGH", riskCap.getValue());
        assertTrue(detailCap.getValue().contains("via=assistant"));
        assertTrue(detailCap.getValue().contains("targetName=生产客服凭证"));
        assertTrue(detailCap.getValue().contains("impacts="));
    }

    @Test
    void gatewayKeyUpdatePassesSecretOnlyFromCardInputAndNeverPersistsOrAuditsSecret() throws Exception {
        LlmProviderView p = new LlmProviderView();
        p.setId("prov-ds");
        p.setName("DeepSeek");
        p.setDefaultModel("deepseek-chat");
        GatewayPolicy policy = new GatewayPolicy();
        policy.setDefaultProviderId("prov-ds");
        GatewayOverview ov = new GatewayOverview();
        ov.setProviders(List.of(p));
        ov.setPolicy(policy);
        when(gatewayService.overview()).thenReturn(ov);
        when(gatewayService.update(eq("prov-ds"), any(LlmProviderRequest.class))).thenReturn(p);

        ToolContext ctx = ToolContext.of(admin, "asc-admin");
        extendedWriteTools.draftManageGatewayChannel(
                mapper.readTree("{\"operation\":\"update_llm_key\",\"target\":\"DeepSeek\"}"), ctx);
        AssistantAction action = ctx.createdActions().get(0);
        assertTrue(action.getPreview().contains("\"secretInput\""));
        assertTrue(action.getPreview().contains("默认通道"));

        when(actionRepository.findByIdAndUserId(action.getId(), admin.getUserId())).thenReturn(Optional.of(action));
        when(actionRepository.claim(eq(action.getId()), eq(admin.getUserId()), any())).thenReturn(1);

        // 未填密钥密码框时拒绝执行且不消耗卡片
        assertThrows(AssistantActionService.ConfirmationMismatchException.class,
                () -> actionService.confirm(action.getId(), new AssistantActionConfirmRequest("DeepSeek", ""), admin));
        verify(actionRepository, never()).claim(anyString(), anyString(), any());

        // 填入新密钥并确认：直传给 gatewayService，但 payload / preview / result / 审计中均严禁出现该明文
        String newSecret = "sk-ultra-secret-value-998877";
        actionService.confirm(action.getId(), new AssistantActionConfirmRequest("DeepSeek", newSecret), admin);

        ArgumentCaptor<LlmProviderRequest> reqCap = ArgumentCaptor.forClass(LlmProviderRequest.class);
        verify(gatewayService).update(eq("prov-ds"), reqCap.capture());
        assertEquals(newSecret, reqCap.getValue().getApiKey());

        assertFalse(action.getPayload().contains(newSecret));
        assertFalse(action.getPreview().contains(newSecret));
        assertFalse(action.getResult().contains(newSecret));
        ArgumentCaptor<String> auditDetail = ArgumentCaptor.forClass(String.class);
        verify(auditRecorder).record(anyString(), anyString(), anyString(), eq("SUCCESS"), isNull(), eq("HIGH"), auditDetail.capture());
        assertFalse(auditDetail.getValue().contains(newSecret), "审计日志绝不能包含密钥明文");
    }

    @Test
    void levelCCardsIncludeCostNoteAndExecuteRebuildAndMcpRefresh() throws Exception {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-1");
        kb.setName("技术文档库");
        kb.setProvider("SPRING_AI");
        kb.setDocumentCount(8);
        when(knowledgeBaseRepository.findById("kb-1")).thenReturn(Optional.of(kb));
        when(authorizationService.canViewKnowledgeBase(dev, kb)).thenReturn(true);
        when(authorizationService.canManageKnowledgeBase(dev, kb)).thenReturn(true);
        when(embeddingConfigService.getActiveConfig()).thenReturn(Optional.of(new EmbeddingConfig()));

        ToolContext ctx = ToolContext.of(dev, "asc-1");
        Map<String, Object> draft = extendedWriteTools.draftRebuildKbIndex(
                mapper.readTree("{\"knowledgeBase\":\"kb-1\"}"), ctx);
        assertEquals("C", draft.get("riskLevel"));
        assertTrue(ctx.createdActions().get(0).getPreview().contains("预计耗时"));

        extendedWriteTools.execute(AssistantExtendedWriteTools.REBUILD_KB_INDEX,
                ctx.createdActions().get(0).getPayload(), null, dev);
        verify(rebuildService).start("kb-1", dev);

        McpServerView srv = new McpServerView();
        srv.setId("mcp-1");
        srv.setName("内部CRM");
        srv.setToolCount(4);
        when(mcpServerService.list()).thenReturn(List.of(srv));
        when(mcpServerService.refreshTools("mcp-1")).thenReturn(srv);
        assertThrows(IllegalStateException.class, () -> extendedWriteTools.draftRefreshMcpServer(
                mapper.readTree("{\"server\":\"mcp-1\"}"), ctx));

        ToolContext adminCtx = ToolContext.of(admin, "asc-2");
        extendedWriteTools.draftRefreshMcpServer(mapper.readTree("{\"server\":\"mcp-1\"}"), adminCtx);
        extendedWriteTools.execute(AssistantExtendedWriteTools.REFRESH_MCP_SERVER,
                adminCtx.createdActions().get(0).getPayload(), null, admin);
        verify(mcpServerService).refreshTools("mcp-1");
    }

    @Test
    void levelAToolsAndGuardrailAndUserManagementRespectPermissions() throws Exception {
        PlatformToolView toolView = new PlatformToolView();
        toolView.setId("t-1");
        toolView.setCode("order_query");
        toolView.setTitle("订单查询");
        toolView.setEnabled(true);
        when(platformToolService.create(any())).thenReturn(toolView);

        ToolContext ctx = ToolContext.of(dev, "asc-1");
        extendedWriteTools.draftCreateHttpTool(mapper.readTree(
                "{\"name\":\"order_query\",\"description\":\"查订单\",\"url\":\"https://api.example.com/order\",\"method\":\"POST\"}"), ctx);
        AssistantWriteTools.ExecutionResult res = extendedWriteTools.execute(
                AssistantExtendedWriteTools.CREATE_HTTP_TOOL, ctx.createdActions().get(0).getPayload(), null, dev);
        assertEquals("t-1", res.resourceId());
        assertTrue(res.readback().contains("order_query"));

        // 禁止修改自己角色
        UserSummaryDto self = new UserSummaryDto();
        self.setId(admin.getUserId());
        self.setUsername("admin");
        self.setRole("SUPER_ADMIN");
        when(userAdminService.listUsers(isNull(), isNull(), isNull())).thenReturn(List.of(self));
        assertThrows(IllegalArgumentException.class, () -> extendedWriteTools.draftManageUser(
                mapper.readTree("{\"operation\":\"change_role\",\"username\":\"admin\",\"role\":\"DEVELOPER\"}"),
                ToolContext.of(admin, "asc-3")));

        // 护栏策略紧急停用给出强影响提示
        GuardrailPolicy gp = new GuardrailPolicy();
        gp.setOwnerId("GLOBAL");
        gp.setKillSwitch(false);
        when(securityPlatformService.loadPolicy(admin)).thenReturn(gp);
        ToolContext gpCtx = ToolContext.of(admin, "asc-4");
        extendedWriteTools.draftUpdateGuardrailPolicy(mapper.readTree("{\"killSwitch\":true}"), gpCtx);
        assertTrue(gpCtx.createdActions().get(0).getPreview().contains("立即拦截"));
    }
}
