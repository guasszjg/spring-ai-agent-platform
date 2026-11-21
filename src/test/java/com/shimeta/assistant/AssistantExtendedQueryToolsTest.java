package com.shimeta.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.model.Agent;
import com.shimeta.model.AgentConversation;
import com.shimeta.model.AgentConversationMessage;
import com.shimeta.model.AgentStatus;
import com.shimeta.model.AgentTemplate;
import com.shimeta.model.AlertRule;
import com.shimeta.model.AuditEvent;
import com.shimeta.model.ClientCredential;
import com.shimeta.model.ConversationDetail;
import com.shimeta.model.GuardrailPolicy;
import com.shimeta.model.KnowledgeBase;
import com.shimeta.model.KnowledgeFaq;
import com.shimeta.model.LlmProvider;
import com.shimeta.model.OpenApiKey;
import com.shimeta.model.PageResult;
import com.shimeta.model.PlatformToolView;
import com.shimeta.model.RoleDto;
import com.shimeta.model.UserRole;
import com.shimeta.model.UserSummaryDto;
import com.shimeta.rag.RetrievedChunk;
import com.shimeta.rag.engine.RetrievalResult;
import com.shimeta.repository.AgentDailyStatRepository;
import com.shimeta.repository.AuditEventRepository;
import com.shimeta.repository.KnowledgeBaseRepository;
import com.shimeta.repository.KnowledgeIndexVersionRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.AgentConversationService;
import com.shimeta.service.AgentPromptVersionService;
import com.shimeta.service.AgentService;
import com.shimeta.service.AgentTemplateService;
import com.shimeta.service.AlertService;
import com.shimeta.service.DifyConfigService;
import com.shimeta.service.EmbeddingConfigService;
import com.shimeta.service.KnowledgeBaseService;
import com.shimeta.service.LlmGatewayService;
import com.shimeta.service.OpenApiKeyService;
import com.shimeta.service.PlatformToolService;
import com.shimeta.service.RagEvaluationService;
import com.shimeta.service.ResourceAuthorizationService;
import com.shimeta.service.SecurityPlatformService;
import com.shimeta.service.ToolUsageService;
import com.shimeta.service.UserAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
class AssistantExtendedQueryToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CurrentActor admin = new CurrentActor("u-admin", "admin", UserRole.SUPER_ADMIN);
    private final CurrentActor dev = new CurrentActor("u-dev", "dev", UserRole.DEVELOPER);
    private final CurrentActor viewer = new CurrentActor("u-view", "viewer", UserRole.VIEWER);

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
    @Mock PlatformToolService platformToolService;
    @Mock AgentPromptVersionService promptVersionService;
    @Mock OpenApiKeyService openApiKeyService;
    @Mock SecurityPlatformService securityPlatformService;
    @Mock AuditEventRepository auditEventRepository;
    @Mock AlertService alertService;
    @Mock RagEvaluationService ragEvaluationService;
    @Mock UserAdminService userAdminService;
    @Mock AgentConversationService conversationService;
    @Mock ToolUsageService toolUsageService;

    private AssistantQueryTools queryTools;
    private AssistantExtendedQueryTools extendedTools;

    @BeforeEach
    void setUp() {
        queryTools = new AssistantQueryTools(agentService, knowledgeBaseService, knowledgeBaseRepository,
                indexVersionRepository, gatewayService, embeddingConfigService, difyConfigService,
                authorizationService, dailyStatRepository, templateService, platformDocsService);
        extendedTools = new AssistantExtendedQueryTools(queryTools, agentService, knowledgeBaseService,
                templateService, platformToolService, promptVersionService, openApiKeyService,
                securityPlatformService, auditEventRepository, alertService, ragEvaluationService,
                userAdminService, conversationService, toolUsageService);
        queryTools.setExtendedQueryTools(extendedTools);
    }

    @Test
    void listAndGetHttpToolNeverLeaksUrlHeadersOrSecrets() throws Exception {
        PlatformToolView tool = new PlatformToolView();
        tool.setId("tool-1");
        tool.setCode("crm_lookup");
        tool.setName("CRM查询");
        tool.setTitle("客户信息查询");
        tool.setCategory("CUSTOM");
        tool.setBuiltin(false);
        tool.setEnabled(true);
        tool.setHasApiKey(true);
        tool.setApiKeyMasked("sk-****9999");
        tool.setConfig(Map.of("url", "https://internal.secret.local/api", "headers", Map.of("Authorization", "Bearer top-secret")));
        when(platformToolService.list()).thenReturn(List.of(tool));

        Map<String, Object> listRes = extendedTools.listResources(mapper.readTree("{\"type\":\"http_tool\"}"), dev);
        String json = mapper.writeValueAsString(listRes);
        assertTrue((Boolean) listRes.get("ok"));
        assertTrue(json.contains("crm_lookup"));
        assertFalse(json.contains("internal.secret.local"), "不得泄露自定义 HTTP 工具地址");
        assertFalse(json.contains("top-secret"), "不得泄露请求头密钥");
        assertFalse(json.contains("sk-****9999"), "不得返回 apiKeyMasked");

        Map<String, Object> getRes = extendedTools.getResource(mapper.readTree("{\"type\":\"http_tool\",\"id\":\"crm_lookup\"}"), dev);
        assertTrue((Boolean) getRes.get("ok"));
        assertFalse(mapper.writeValueAsString(getRes).contains("internal.secret.local"));
    }

    @Test
    void listApiKeyTerminalAlertGuardrailAndAuditRespectPermissionsAndMaskSecrets() throws Exception {
        OpenApiKey key = new OpenApiKey();
        key.setId("key-1");
        key.setName("客服调用凭证");
        key.setKeyPrefix("sk-live-12345678");
        key.setKeyHash("deadbeefhashvalue");
        key.setStatus("ACTIVE");
        key.setScopes(List.of("agent:chat"));
        when(openApiKeyService.listForActor(dev)).thenReturn(List.of(key));

        Map<String, Object> keyRes = extendedTools.listResources(mapper.readTree("{\"type\":\"api_key\"}"), dev);
        String keyJson = mapper.writeValueAsString(keyRes);
        assertTrue(keyJson.contains("sk-live-12345678"));
        assertFalse(keyJson.contains("deadbeefhashvalue"), "严禁返回 keyHash");

        ClientCredential client = new ClientCredential();
        client.setId("cli-1");
        client.setClientType("MACHINE_ID");
        client.setClientId("dev-box-01");
        client.setClientIdHash("clientsecrethash");
        client.setLabel("客服终端");
        client.setStatus("ACTIVE");
        when(securityPlatformService.listClients(dev)).thenReturn(List.of(client));
        Map<String, Object> cliRes = extendedTools.listResources(mapper.readTree("{\"type\":\"terminal\"}"), dev);
        assertFalse(mapper.writeValueAsString(cliRes).contains("clientsecrethash"));

        AlertRule rule = new AlertRule();
        rule.setId("alert-1");
        rule.setName("高风险告警");
        rule.setMetric("HIGH_RISK_COUNT");
        rule.setThreshold(5.0);
        rule.setWebhookUrl("https://hooks.slack.com/services/SECRET/WEBHOOK");
        rule.setWebhookSecret("whsec_super_secret");
        rule.setEnabled(true);
        when(alertService.listRules(dev)).thenReturn(List.of(rule));
        Map<String, Object> alertRes = extendedTools.listResources(mapper.readTree("{\"type\":\"alert\"}"), dev);
        String alertJson = mapper.writeValueAsString(alertRes);
        assertTrue(alertJson.contains("\"webhookConfigured\":true"));
        assertFalse(alertJson.contains("hooks.slack.com"), "严禁泄露 webhookUrl");
        assertFalse(alertJson.contains("whsec_super_secret"), "严禁泄露 webhookSecret");

        GuardrailPolicy policy = new GuardrailPolicy();
        policy.setOwnerId("GLOBAL");
        policy.setPiiMask(true);
        policy.setSensitiveWords(List.of("机密词1", "机密词2"));
        when(securityPlatformService.loadPolicy(viewer)).thenReturn(policy);
        Map<String, Object> policyRes = extendedTools.getResource(mapper.readTree("{\"type\":\"guardrail_policy\"}"), viewer);
        String policyJson = mapper.writeValueAsString(policyRes);
        assertTrue(policyJson.contains("\"sensitiveWordCount\":2"));
        assertFalse(policyJson.contains("机密词1"));

        AuditEvent ev = new AuditEvent();
        ev.setId("aud-1");
        ev.setOwnerId("u-dev");
        ev.setAction("key.create");
        ev.setResult("SUCCESS");
        ev.setRiskLevel("LOW");
        ev.setOccurredAt(LocalDateTime.now());
        when(auditEventRepository.findByOwnerIdOrderByOccurredAtDesc(eq("u-dev"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ev)));
        Map<String, Object> auditRes = extendedTools.listResources(mapper.readTree("{\"type\":\"audit_log\"}"), dev);
        assertEquals(1, auditRes.get("returned"));
        verify(auditEventRepository, never()).findAllByOrderByOccurredAtDesc(any(Pageable.class));
    }

    @Test
    void listUsersAndRolesAreSuperAdminOnly() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> extendedTools.listResources(mapper.readTree("{\"type\":\"user\"}"), dev));
        assertThrows(IllegalStateException.class,
                () -> extendedTools.listResources(mapper.readTree("{\"type\":\"role\"}"), viewer));

        UserSummaryDto u = new UserSummaryDto();
        u.setId("u-2");
        u.setUsername("alice");
        u.setNickname("爱丽丝");
        u.setRole("DEVELOPER");
        u.setRoleName("开发者");
        u.setStatus("ACTIVE");
        when(userAdminService.listUsers(isNull(), isNull(), isNull())).thenReturn(List.of(u));
        when(userAdminService.getRolesSummary()).thenReturn(List.of(
                new RoleDto("SUPER_ADMIN", "超级管理员", "全权", true, 1, Set.of("*"))));

        Map<String, Object> userRes = extendedTools.listResources(mapper.readTree("{\"type\":\"user\"}"), admin);
        assertEquals(1, userRes.get("total"));
        Map<String, Object> roleRes = extendedTools.listResources(mapper.readTree("{\"type\":\"role\"}"), admin);
        assertEquals(1, roleRes.get("total"));
    }

    @Test
    void listPromptVersionsFaqsTemplatesAndRagEvalRunsWorkWithParentResolution() throws Exception {
        Agent agent = new Agent();
        agent.setId("ag-1");
        agent.setName("售后客服");
        when(agentService.getById("ag-1", dev)).thenReturn(Optional.of(agent));
        when(promptVersionService.list("ag-1", dev)).thenReturn(List.of(
                Map.of("id", "ver-2", "versionNo", 2, "sourceLabel", "编辑", "current", true)));
        when(promptVersionService.get("ag-1", "ver-2", dev)).thenReturn(
                Map.of("id", "ver-2", "versionNo", 2, "systemPrompt", "你是专业售后客服"));

        Map<String, Object> pvList = extendedTools.listResources(
                mapper.readTree("{\"type\":\"prompt_version\",\"parent\":\"ag-1\"}"), dev);
        assertEquals(1, pvList.get("total"));
        Map<String, Object> pvGet = extendedTools.getResource(
                mapper.readTree("{\"type\":\"prompt_version\",\"parent\":\"ag-1\",\"id\":\"v2\"}"), dev);
        assertTrue(mapper.writeValueAsString(pvGet).contains("你是专业售后客服"));

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-1");
        kb.setName("售后手册");
        kb.setProvider("SPRING_AI");
        when(knowledgeBaseRepository.findById("kb-1")).thenReturn(Optional.of(kb));
        when(authorizationService.canViewKnowledgeBase(dev, kb)).thenReturn(true);

        KnowledgeFaq faq = new KnowledgeFaq("kb-1", "怎么退货？", "7天内可申请退货", "售后", "TEXT");
        faq.setId("faq-1");
        when(knowledgeBaseService.searchFaqs("kb-1", null, null, 1, 10, dev))
                .thenReturn(new PageResult<>(List.of(faq), 1, 1, 10));
        Map<String, Object> faqRes = extendedTools.listResources(
                mapper.readTree("{\"type\":\"faq\",\"parent\":\"kb-1\"}"), dev);
        assertEquals(1, faqRes.get("returned"));

        RagEvaluationService.RunView run = new RagEvaluationService.RunView(
                "run-1", "kb-1", "SPRING_AI", "混合 50:50", Map.of("topK", 5), "fp", "DONE",
                20, 20, 0, 0, null, 5, 0.9, 0.9, 0.85, 0.82, 120.5, LocalDateTime.now(), List.of());
        when(ragEvaluationService.listRuns("kb-1", dev)).thenReturn(List.of(run));
        Map<String, Object> evalRes = extendedTools.listResources(
                mapper.readTree("{\"type\":\"rag_eval_run\",\"parent\":\"kb-1\"}"), dev);
        assertEquals(1, evalRes.get("total"));

        AgentTemplate tpl = new AgentTemplate();
        tpl.setId("tpl-1");
        tpl.setName("客服模板");
        tpl.setCategory("客服");
        when(templateService.list(isNull(), isNull(), eq(dev))).thenReturn(List.of(tpl));
        Map<String, Object> tplRes = extendedTools.listResources(mapper.readTree("{\"type\":\"template\"}"), dev);
        assertEquals(1, tplRes.get("total"));
    }

    @Test
    void runRetrievalTestReturnsRankedHitsAndZeroHitHint() throws Exception {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-1");
        kb.setName("产品手册");
        kb.setProvider("SPRING_AI");
        when(knowledgeBaseRepository.findById("kb-1")).thenReturn(Optional.of(kb));
        when(authorizationService.canViewKnowledgeBase(dev, kb)).thenReturn(true);

        RetrievedChunk hit = new RetrievedChunk("c1", "d1", null, "退换货政策.pdf", null, 3, 0, 100,
                "支持7天无理由退货", null, 0.91234, 0.88, 0.95, 0.92, "HYBRID", 20, Map.of());
        when(knowledgeBaseService.testRetrieval(eq("kb-1"), any(), eq(dev)))
                .thenReturn(RetrievalResult.of("怎么退货", List.of(hit), null, 45L));

        Map<String, Object> res = extendedTools.runRetrievalTest(
                mapper.readTree("{\"knowledgeBase\":\"kb-1\",\"query\":\"怎么退货\",\"topK\":3}"), dev);
        assertEquals(1, res.get("hitCount"));
        assertEquals(45L, res.get("latencyMs"));
        String json = mapper.writeValueAsString(res);
        assertTrue(json.contains("退换货政策.pdf"));
        assertTrue(json.contains("0.9123"));

        when(knowledgeBaseService.testRetrieval(eq("kb-1"), any(), eq(dev)))
                .thenReturn(RetrievalResult.of("不存在的内容", List.of(), null, 12L));
        Map<String, Object> empty = extendedTools.runRetrievalTest(
                mapper.readTree("{\"knowledgeBase\":\"kb-1\",\"query\":\"不存在的内容\"}"), dev);
        assertEquals(0, empty.get("hitCount"));
        assertTrue(String.valueOf(empty.get("note")).contains("命中 0 条"));
    }

    @Test
    void getAgentLogsToolUsageAndUpgradedDiagnoseDetectFailures() throws Exception {
        Agent agent = new Agent();
        agent.setId("ag-1");
        agent.setName("订单助手");
        agent.setStatus(AgentStatus.RUNNING);
        when(agentService.getById("ag-1", admin)).thenReturn(Optional.of(agent));
        when(authorizationService.canRunAgent(admin, agent)).thenReturn(true);
        LlmProvider provider = new LlmProvider();
        provider.setName("DeepSeek");
        provider.setDefaultModel("deepseek-chat");
        when(gatewayService.resolveRoute(any())).thenReturn(Optional.of(
                new LlmGatewayService.ResolvedRoute(provider, null, 30000, 0, "k", null)));
        when(dailyStatRepository.findByAgentIdAndStatDateBetween(eq("ag-1"), any(), any())).thenReturn(List.of());

        AgentConversation conv = new AgentConversation();
        conv.setId("conv-1");
        conv.setAgentId("ag-1");
        conv.setTitle("查订单状态");
        conv.setAccount("zhangsan");
        conv.setMessageCount(2);
        conv.setLastModel("deepseek-chat");
        conv.setTotalTokens(320);
        conv.setUpdatedAt(LocalDateTime.now());
        when(conversationService.listLogs(eq(agent), eq(admin), eq("7days"), isNull(), eq("updatedAt"), eq("desc"), eq(1), anyInt()))
                .thenReturn(new PageResult<>(List.of(conv), 1, 1, 10));

        AgentConversationMessage uMsg = new AgentConversationMessage();
        uMsg.setRole("user");
        uMsg.setContent("帮我查订单 1001");
        AgentConversationMessage aMsg = new AgentConversationMessage();
        aMsg.setRole("assistant");
        aMsg.setContent("调用失败：HTTP 502 上游接口超时");
        aMsg.setLatencyMs(18000L);
        when(conversationService.getDetail(agent, admin, "conv-1"))
                .thenReturn(Optional.of(new ConversationDetail(conv, List.of(uMsg, aMsg))));

        Map<String, Object> logsRes = extendedTools.getAgentLogs(mapper.readTree("{\"agent\":\"ag-1\"}"), admin);
        assertEquals(1, logsRes.get("suspectedFailureCount"));
        assertTrue(mapper.writeValueAsString(logsRes).contains("HTTP 502"));

        PlatformToolView meta = new PlatformToolView();
        meta.setCode("order_api");
        meta.setTitle("订单接口");
        meta.setUnitPrice(new BigDecimal("0.01"));
        when(platformToolService.list()).thenReturn(List.of(meta));
        when(toolUsageService.summary(List.of("ag-1"))).thenReturn(Map.of(
                "order_api", new ToolUsageService.ToolStats(2, 10, 25, 4, 8000)));

        Map<String, Object> usageRes = extendedTools.getToolUsage(mapper.readTree("{\"agent\":\"ag-1\"}"), admin);
        String usageJson = mapper.writeValueAsString(usageRes);
        assertTrue(usageJson.contains("order_api"));
        assertTrue(usageJson.contains("\"monthFailureRatePercent\":40.0"));
        assertTrue(usageJson.contains("0.1000"));

        // 升级后的 diagnose_agent 应包含近期对话日志异常与高失败率工具告警
        Map<String, Object> diag = queryTools.diagnoseAgent(mapper.readTree("{\"agent\":\"ag-1\"}"), admin);
        String diagJson = mapper.writeValueAsString(diag);
        assertTrue(diagJson.contains("近期对话日志"), "diagnose_agent 应检查近期对话日志");
        assertTrue(diagJson.contains("工具调用失败率"), "diagnose_agent 应检查高失败率工具");
        assertTrue(diagJson.contains("order_api"));
    }
}
