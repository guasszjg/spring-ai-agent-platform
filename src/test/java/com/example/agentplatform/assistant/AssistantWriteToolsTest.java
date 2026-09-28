package com.example.agentplatform.assistant;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.model.AssistantAction;
import com.example.agentplatform.model.CreateOpenApiKeyRequest;
import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.repository.AssistantActionRepository;
import com.example.agentplatform.repository.KnowledgeBaseRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.AgentService;
import com.example.agentplatform.service.AgentTemplateService;
import com.example.agentplatform.service.EmbeddingConfigService;
import com.example.agentplatform.service.KnowledgeBaseService;
import com.example.agentplatform.service.OpenApiKeyService;
import com.example.agentplatform.service.ResourceAuthorizationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssistantWriteToolsTest {

    @Mock AssistantQueryTools queryTools;
    @Mock AgentService agentService;
    @Mock AgentRepository agentRepository;
    @Mock AgentTemplateService templateService;
    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock EmbeddingConfigService embeddingConfigService;
    @Mock ResourceAuthorizationService authorizationService;
    @Mock AssistantActionRepository actionRepository;
    @Mock OpenApiKeyService openApiKeyService;

    private final ObjectMapper mapper = new ObjectMapper();
    private final CurrentActor developer = new CurrentActor("u-dev", "dev", UserRole.DEVELOPER);
    private AssistantWriteTools tools;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        tools = new AssistantWriteTools(queryTools, agentService, agentRepository, templateService, knowledgeBaseService,
                knowledgeBaseRepository, embeddingConfigService, authorizationService, actionRepository, openApiKeyService);
        ctx = ToolContext.of(developer, "asc-1");
        when(actionRepository.save(any())).thenAnswer(inv -> {
            AssistantAction a = inv.getArgument(0);
            a.setId("act-1");
            return a;
        });
        when(embeddingConfigService.getActiveConfig()).thenReturn(Optional.empty());
    }

    private JsonNode args(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static Agent agent(String id, String name, String prompt) {
        Agent a = new Agent();
        a.setId(id);
        a.setName(name);
        a.setSystemPrompt(prompt);
        a.setStatus(AgentStatus.RUNNING);
        a.setTemperature(0.2);
        a.setKnowledgeBaseIds(List.of("kb-old"));
        a.setTags(List.of("售后"));
        return a;
    }

    private static KnowledgeBase kb(String id, String name) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setName(name);
        return kb;
    }

    @Test
    void createAgentOnlyGeneratesPendingActionWithAutoCode() throws Exception {
        Map<String, Object> result = tools.draftCreateAgent(args(
                "{\"name\":\"售后退货客服\",\"category\":\"知识库客服\",\"systemPrompt\":\"你是售后客服……\"}"), ctx);

        assertEquals(true, result.get("ok"));
        assertEquals("act-1", result.get("pendingActionId"));
        verify(agentService, never()).create(any(), any());
        AssistantAction action = ctx.createdActions().get(0);
        assertEquals(AssistantAction.PENDING, action.getStatus());
        assertEquals("W1", action.getRiskLevel());
        assertEquals("asc-1", action.getConversationId());
        assertEquals("u-dev", action.getUserId());
        assertTrue(action.getExpiresAt().isAfter(java.time.LocalDateTime.now().plusMinutes(9)));
        assertTrue(action.getExpiresAt().isBefore(java.time.LocalDateTime.now().plusMinutes(11)));
        JsonNode payload = mapper.readTree(action.getPayload());
        assertTrue(payload.path("code").asText().startsWith("agent_"));
        assertTrue(payload.path("autoCode").asBoolean());
        assertTrue(action.getPreview().contains("跟随网关默认通道"));
    }

    @Test
    void createAgentRejectsInvalidCategoryTakenCodeAndMissingPrompt() {
        when(agentRepository.existsByCode("taken")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> tools.draftCreateAgent(args(
                "{\"name\":\"a\",\"category\":\"随便\",\"systemPrompt\":\"p\"}"), ctx));
        assertThrows(IllegalArgumentException.class, () -> tools.draftCreateAgent(args(
                "{\"name\":\"a\",\"code\":\"taken\",\"systemPrompt\":\"p\"}"), ctx));
        assertThrows(IllegalArgumentException.class, () -> tools.draftCreateAgent(args("{\"name\":\"a\"}"), ctx));
        assertTrue(ctx.createdActions().isEmpty());
    }

    @Test
    void createAgentRefusesKnowledgeBaseWithoutUsePermission() {
        KnowledgeBase secret = kb("kb-secret", "薪资制度");
        when(queryTools.resolveKnowledgeBase("薪资制度", developer)).thenReturn(secret);
        when(authorizationService.canUseKnowledgeBase(developer, secret)).thenReturn(false);

        var e = assertThrows(IllegalArgumentException.class, () -> tools.draftCreateAgent(args(
                "{\"name\":\"a\",\"systemPrompt\":\"p\",\"knowledgeBases\":[\"薪资制度\"]}"), ctx));
        assertTrue(e.getMessage().contains("使用权限"));
        assertTrue(ctx.createdActions().isEmpty());
    }

    @Test
    void systemAgentCannotBeModifiedByDeveloper() {
        Agent system = agent("agent-sys", "系统客服", "旧提示词");
        system.setIsSystem(true);
        when(queryTools.resolveAgent("系统客服", developer)).thenReturn(new AssistantQueryTools.AgentMatch(system, null));
        when(authorizationService.canManageAgent(developer, system)).thenReturn(false);

        var e = assertThrows(IllegalArgumentException.class, () -> tools.draftUpdateAgentPrompt(args(
                "{\"agent\":\"系统客服\",\"systemPrompt\":\"新提示词\"}"), ctx));
        assertTrue(e.getMessage().contains("系统公共智能体"));
        assertTrue(ctx.createdActions().isEmpty());
    }

    @Test
    void updatePromptCardShowsDiffAndRejectsIdenticalPrompt() throws Exception {
        Agent a = agent("agent-1", "售后客服", "旧提示词");
        when(queryTools.resolveAgent("售后客服", developer)).thenReturn(new AssistantQueryTools.AgentMatch(a, null));
        when(authorizationService.canManageAgent(developer, a)).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> tools.draftUpdateAgentPrompt(args(
                "{\"agent\":\"售后客服\",\"systemPrompt\":\"旧提示词\"}"), ctx));

        tools.draftUpdateAgentPrompt(args("{\"agent\":\"售后客服\",\"systemPrompt\":\"新提示词\"}"), ctx);
        JsonNode diff = mapper.readTree(ctx.createdActions().get(0).getPreview()).path("diff");
        assertEquals("旧提示词", diff.path("before").asText());
        assertEquals("新提示词", diff.path("after").asText());
        assertEquals("W2", ctx.createdActions().get(0).getRiskLevel());
    }

    @Test
    void updatePromptExecutionRefusesWhenPromptChangedSinceCardWasCreated() {
        Agent a = agent("agent-1", "售后客服", "别人刚改过的提示词");
        when(agentService.getById("agent-1", developer)).thenReturn(Optional.of(a));
        when(authorizationService.canManageAgent(developer, a)).thenReturn(true);
        String payload = "{\"agentId\":\"agent-1\",\"systemPrompt\":\"新提示词\",\"baselineSha256\":\""
                + PlatformDocsService.sha256("旧提示词".getBytes(StandardCharsets.UTF_8)) + "\"}";

        var e = assertThrows(IllegalStateException.class, () -> tools.execute(AssistantWriteTools.UPDATE_AGENT_PROMPT, payload, developer));
        assertTrue(e.getMessage().contains("已被修改"));
        verify(agentService, never()).update(anyString(), any(), any());
    }

    @Test
    void updatePromptExecutionKeepsExistingBindingsStatusAndSettings() {
        Agent a = agent("agent-1", "售后客服", "旧提示词");
        when(agentService.getById("agent-1", developer)).thenReturn(Optional.of(a));
        when(authorizationService.canManageAgent(developer, a)).thenReturn(true);
        String payload = "{\"agentId\":\"agent-1\",\"systemPrompt\":\"新提示词\",\"baselineSha256\":\""
                + PlatformDocsService.sha256("旧提示词".getBytes(StandardCharsets.UTF_8)) + "\"}";

        var result = tools.execute(AssistantWriteTools.UPDATE_AGENT_PROMPT, payload, developer);

        ArgumentCaptor<Agent> patch = ArgumentCaptor.forClass(Agent.class);
        verify(agentService).update(eq("agent-1"), patch.capture(), eq(developer));
        assertEquals("新提示词", patch.getValue().getSystemPrompt());
        assertEquals(List.of("kb-old"), patch.getValue().getKnowledgeBaseIds());
        assertEquals(AgentStatus.RUNNING, patch.getValue().getStatus());
        assertEquals(0.2, patch.getValue().getTemperature());
        assertEquals(List.of("售后"), patch.getValue().getTags());
        assertEquals(null, patch.getValue().getIsSystem());
        assertEquals("/debug/agent-1", result.linkUrl());
    }

    @Test
    void bindKnowledgeBaseAppendsToCurrentBindingsAtExecutionTime() {
        Agent a = agent("agent-1", "售后客服", "p");
        a.setKnowledgeBaseIds(List.of("kb-old", "kb-added-meanwhile"));
        when(agentService.getById("agent-1", developer)).thenReturn(Optional.of(a));
        when(authorizationService.canManageAgent(developer, a)).thenReturn(true);
        when(knowledgeBaseRepository.findById("kb-new")).thenReturn(Optional.of(kb("kb-new", "售后规范")));

        tools.execute(AssistantWriteTools.BIND_KNOWLEDGE_BASE,
                "{\"agentId\":\"agent-1\",\"knowledgeBaseId\":\"kb-new\",\"operation\":\"bind\"}", developer);

        ArgumentCaptor<Agent> patch = ArgumentCaptor.forClass(Agent.class);
        verify(agentService).update(eq("agent-1"), patch.capture(), eq(developer));
        assertEquals(List.of("kb-old", "kb-added-meanwhile", "kb-new"), patch.getValue().getKnowledgeBaseIds());
    }

    @Test
    void bindRejectsAlreadyBoundKnowledgeBase() {
        Agent a = agent("agent-1", "售后客服", "p");
        when(queryTools.resolveAgent("售后客服", developer)).thenReturn(new AssistantQueryTools.AgentMatch(a, null));
        when(authorizationService.canManageAgent(developer, a)).thenReturn(true);
        when(queryTools.resolveKnowledgeBase("旧库", developer)).thenReturn(kb("kb-old", "旧库"));

        var e = assertThrows(IllegalArgumentException.class, () -> tools.draftBindKnowledgeBase(args(
                "{\"agent\":\"售后客服\",\"knowledgeBase\":\"旧库\"}"), ctx));
        assertTrue(e.getMessage().contains("已经绑定"));
    }

    @Test
    void setStatusRejectsNoOpAndUnsupportedStatus() {
        Agent a = agent("agent-1", "售后客服", "p");
        when(queryTools.resolveAgent("售后客服", developer)).thenReturn(new AssistantQueryTools.AgentMatch(a, null));
        when(authorizationService.canManageAgent(developer, a)).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> tools.draftSetAgentStatus(args(
                "{\"agent\":\"售后客服\",\"status\":\"RUNNING\"}"), ctx));
        assertThrows(IllegalArgumentException.class, () -> tools.draftSetAgentStatus(args(
                "{\"agent\":\"售后客服\",\"status\":\"DELETED\"}"), ctx));
        assertTrue(ctx.createdActions().isEmpty());
    }

    @Test
    void ambiguousAgentReturnsCandidatesWithoutCreatingAction() throws Exception {
        when(queryTools.resolveAgent("客服", developer)).thenReturn(new AssistantQueryTools.AgentMatch(null,
                Map.of("ok", false, "ambiguous", true, "error", "匹配到多个智能体")));

        Map<String, Object> result = tools.draftSetAgentStatus(args("{\"agent\":\"客服\",\"status\":\"DISABLED\"}"), ctx);

        assertEquals(false, result.get("ok"));
        assertTrue(ctx.createdActions().isEmpty());
    }

    @Test
    void createKnowledgeBaseCardWarnsWhenNoEmbeddingModel() throws Exception {
        tools.draftCreateKnowledgeBase(args("{\"name\":\"售后规范\"}"), ctx);
        String preview = ctx.createdActions().get(0).getPreview();
        assertTrue(preview.contains("平台内置引擎"));
        assertTrue(preview.contains("未激活向量模型"));
        assertFalse(preview.contains("DIFY"));
    }

    // ==================== P3：多步编排与开放凭证 ====================

    private static final String AGENT_CARD = "act-aaaaaaaa11111111";
    private static final String KB_CARD = "act-bbbbbbbb22222222";

    private AssistantAction card(String id, String tool, String status, String payload) {
        AssistantAction a = new AssistantAction();
        a.setId(id);
        a.setUserId("u-dev");
        a.setConversationId("asc-1");
        a.setToolName(tool);
        a.setTitle(tool + " 卡片");
        a.setStatus(status);
        a.setPayload(payload);
        a.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(5));
        when(actionRepository.findByIdAndUserId(id, "u-dev")).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void bindCanReferencePendingCreateCardsAndRecordsDependencies() throws Exception {
        card(AGENT_CARD, AssistantWriteTools.CREATE_AGENT, AssistantAction.PENDING, "{\"name\":\"退货助手\",\"knowledgeBaseIds\":[]}");
        card(KB_CARD, AssistantWriteTools.CREATE_KNOWLEDGE_BASE, AssistantAction.PENDING, "{\"name\":\"退货库\"}");

        tools.draftBindKnowledgeBase(args("{\"agent\":\"" + AGENT_CARD + "\",\"knowledgeBase\":\"" + KB_CARD + "\"}"), ctx);

        AssistantAction action = ctx.createdActions().get(0);
        JsonNode payload = mapper.readTree(action.getPayload());
        assertEquals(AGENT_CARD, payload.path("agentFrom").asText());
        assertEquals(KB_CARD, payload.path("knowledgeBaseFrom").asText());
        assertFalse(payload.has("agentId"));
        assertTrue(action.getPreview().contains("待创建，需先确认上一张卡片"));
        assertEquals(List.of(AGENT_CARD, KB_CARD), tools.dependencies(action));
        verify(queryTools, never()).resolveAgent(anyString(), any());
    }

    @Test
    void executedCreateCardResolvesToRealResource() throws Exception {
        AssistantAction created = card(KB_CARD, AssistantWriteTools.CREATE_KNOWLEDGE_BASE, AssistantAction.EXECUTED, "{\"name\":\"退货库\"}");
        created.setResourceId("kb-9");
        KnowledgeBase kb = kb("kb-9", "退货库");
        when(knowledgeBaseRepository.findById("kb-9")).thenReturn(Optional.of(kb));
        when(authorizationService.canManageKnowledgeBase(developer, kb)).thenReturn(true);

        tools.draftAddFaq(args("{\"knowledgeBase\":\"" + KB_CARD + "\",\"question\":\"几天可退\",\"answer\":\"7 天\"}"), ctx);

        JsonNode payload = mapper.readTree(ctx.createdActions().get(0).getPayload());
        assertEquals("kb-9", payload.path("knowledgeBaseId").asText());
        assertFalse(payload.has("knowledgeBaseFrom"));
    }

    @Test
    void cannotReferenceCardFromOtherConversationWrongTypeOrCancelled() {
        AssistantAction other = card(AGENT_CARD, AssistantWriteTools.CREATE_AGENT, AssistantAction.PENDING, "{\"name\":\"x\"}");
        other.setConversationId("asc-other");
        assertThrows(IllegalArgumentException.class, () -> tools.draftCreateApiKey(args("{\"agent\":\"" + AGENT_CARD + "\"}"), ctx));

        card(KB_CARD, AssistantWriteTools.CREATE_AGENT, AssistantAction.PENDING, "{\"name\":\"x\"}");
        assertThrows(IllegalArgumentException.class, () -> tools.draftAddFaq(args(
                "{\"knowledgeBase\":\"" + KB_CARD + "\",\"question\":\"q\",\"answer\":\"a\"}"), ctx));

        card("act-cccccccc33333333", AssistantWriteTools.CREATE_AGENT, AssistantAction.CANCELLED, "{\"name\":\"x\"}");
        assertThrows(IllegalArgumentException.class, () -> tools.draftCreateApiKey(args("{\"agent\":\"act-cccccccc33333333\"}"), ctx));
        assertTrue(ctx.createdActions().isEmpty());
    }

    @Test
    void dependentCardCannotExecuteBeforePredecessor() {
        AssistantAction dep = card(KB_CARD, AssistantWriteTools.CREATE_KNOWLEDGE_BASE, AssistantAction.PENDING, "{\"name\":\"退货库\"}");
        AssistantAction faq = new AssistantAction();
        faq.setPayload("{\"knowledgeBaseFrom\":\"" + KB_CARD + "\",\"question\":\"q\",\"answer\":\"a\"}");

        assertTrue(tools.unmetDependency(faq, developer).contains("请先确认上一张卡片"));
        assertThrows(IllegalStateException.class, () -> tools.execute(AssistantWriteTools.ADD_FAQ, faq.getPayload(), developer));

        dep.setStatus(AssistantAction.EXECUTED);
        dep.setResourceId("kb-9");
        when(knowledgeBaseService.createFaq(eq("kb-9"), any(), eq(developer))).thenReturn(new com.example.agentplatform.model.KnowledgeFaq());
        assertEquals(null, tools.unmetDependency(faq, developer));
        var result = tools.execute(AssistantWriteTools.ADD_FAQ, faq.getPayload(), developer);
        assertEquals("kb-9", result.resourceId());
    }

    @Test
    void createAgentRejectsPendingKnowledgeBaseRefWithGuidance() {
        var e = assertThrows(IllegalArgumentException.class, () -> tools.draftCreateAgent(args(
                "{\"name\":\"退货助手\",\"systemPrompt\":\"p\",\"knowledgeBases\":[\"" + KB_CARD + "\"]}"), ctx));
        assertTrue(e.getMessage().contains("bind_knowledge_base"));
    }

    @Test
    void createApiKeyCardAndExecutionScopedToAgentWithSecretOnlyInResult() throws Exception {
        Agent a = agent("agent-1", "售后客服", "p");
        when(queryTools.resolveAgent("售后客服", developer)).thenReturn(new AssistantQueryTools.AgentMatch(a, null));
        when(agentService.getById("agent-1", developer)).thenReturn(Optional.of(a));
        when(authorizationService.canManageAgent(developer, a)).thenReturn(true);

        Map<String, Object> drafted = tools.draftCreateApiKey(args("{\"agent\":\"售后客服\"}"), ctx);
        assertEquals(true, drafted.get("ok"));
        AssistantAction action = ctx.createdActions().get(0);
        assertEquals(AssistantWriteTools.CREATE_API_KEY, action.getToolName());
        assertTrue(action.getPreview().contains("只在卡片中显示一次"));
        verify(openApiKeyService, never()).create(any(), any());

        when(openApiKeyService.create(any(), eq(developer))).thenReturn(Map.of(
                "key", Map.of("id", "key-1", "keyPrefix", "sk-am-abc"), "plaintext", "sk-am-abcdefghijklmnopqrstuvwxyz"));
        var result = tools.execute(AssistantWriteTools.CREATE_API_KEY, action.getPayload(), developer);

        ArgumentCaptor<CreateOpenApiKeyRequest> req = ArgumentCaptor.forClass(CreateOpenApiKeyRequest.class);
        verify(openApiKeyService).create(req.capture(), eq(developer));
        assertEquals(List.of("chat"), req.getValue().getScopes());
        assertEquals(List.of("agent-1"), req.getValue().getAgentScope());
        assertEquals("u-dev", req.getValue().getOwnerId());
        assertEquals("key-1", result.resourceId());
        assertEquals("sk-am-abcdefghijklmnopqrstuvwxyz", result.secret());
        assertFalse(result.message().contains("abcdefghijklmnop"));
    }

    @Test
    void createApiKeyRequiresManagePermission() {
        Agent a = agent("agent-1", "系统客服", "p");
        when(queryTools.resolveAgent("系统客服", developer)).thenReturn(new AssistantQueryTools.AgentMatch(a, null));
        when(authorizationService.canManageAgent(developer, a)).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> tools.draftCreateApiKey(args("{\"agent\":\"系统客服\"}"), ctx));
        assertTrue(ctx.createdActions().isEmpty());
    }
}
