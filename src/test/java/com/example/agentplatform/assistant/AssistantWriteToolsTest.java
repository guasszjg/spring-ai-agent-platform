package com.example.agentplatform.assistant;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.model.AssistantAction;
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

    private final ObjectMapper mapper = new ObjectMapper();
    private final CurrentActor developer = new CurrentActor("u-dev", "dev", UserRole.DEVELOPER);
    private AssistantWriteTools tools;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        tools = new AssistantWriteTools(queryTools, agentService, agentRepository, templateService, knowledgeBaseService,
                knowledgeBaseRepository, embeddingConfigService, authorizationService, actionRepository);
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
}
