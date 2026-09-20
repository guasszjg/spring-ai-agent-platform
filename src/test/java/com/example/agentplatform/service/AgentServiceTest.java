package com.example.agentplatform.service;

import com.example.agentplatform.config.ToolConfigSanitizer;
import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.repository.AgentDailyStatRepository;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.repository.KnowledgeBaseRepository;
import com.example.agentplatform.repository.ResourceGrantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private AgentDailyStatRepository dailyStatRepository;

    @Mock
    private AgentConversationService conversationService;

    @Mock
    private AgentToolSecretService toolSecretService;

    @Mock
    private ResourceAuthorizationService resourceAuthService;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private ResourceGrantRepository resourceGrantRepository;

    @Mock
    private OwnerNameResolver ownerNameResolver;

    private ToolConfigSanitizer toolConfigSanitizer = new ToolConfigSanitizer();

    private AgentService agentService;

    @BeforeEach
    void setUp() {
        agentService = new AgentService(
                agentRepository,
                dailyStatRepository,
                conversationService,
                toolConfigSanitizer,
                toolSecretService,
                resourceAuthService,
                knowledgeBaseRepository,
                resourceGrantRepository,
                ownerNameResolver
        );
    }

    @Test
    void copyAgent_clonesPromptAndTools_resetsStatsAndGeneratesNewIdentity() {
        Agent source = new Agent();
        source.setId("orig-id-123");
        source.setName("代码审计专家");
        source.setCode("code_auditor");
        source.setAvatar("💻");
        source.setCategory("代码研发");
        source.setDescription("专注代码审计与重构");
        source.setModelName("deepseek-chat");
        source.setSystemPrompt("你是一名架构级代码审计专家...");
        source.setTemperature(0.2);
        source.setTopP(0.95);
        source.setMaxTokens(4096);
        source.setTags(List.of("安全", "代码审计"));
        source.setToolsConfig("{\"bocha\":{\"enabled\":true}}");
        source.setKnowledgeBaseIds(List.of("kb-a", "kb-b"));
        source.setApiKey("sk-agent-old-secret-key");
        source.setCallCount(999L);
        source.setAvgResponseTimeMs(450.5);
        source.setStatus(AgentStatus.RUNNING);

        when(agentRepository.findById("orig-id-123")).thenReturn(Optional.of(source));
        when(resourceAuthService.canCopyAgent(any(), any())).thenReturn(true);
        when(resourceAuthService.canUseKnowledgeBase(any(), any())).thenReturn(true);
        KnowledgeBase kbA = new KnowledgeBase(); kbA.setId("kb-a");
        KnowledgeBase kbB = new KnowledgeBase(); kbB.setId("kb-b");
        when(knowledgeBaseRepository.findById("kb-a")).thenReturn(Optional.of(kbA));
        when(knowledgeBaseRepository.findById("kb-b")).thenReturn(Optional.of(kbB));

        when(agentRepository.existsByCode("code_auditor_copy")).thenReturn(false);
        when(agentRepository.save(any(Agent.class))).thenAnswer(invocation -> {
            Agent a = invocation.getArgument(0);
            a.setId("cloned-new-id");
            return a;
        });

        Agent cloned = agentService.copyAgent("orig-id-123");

        assertThat(cloned).isNotNull();
        assertThat(cloned.getName()).isEqualTo("代码审计专家 (副本)");
        assertThat(cloned.getCode()).isEqualTo("code_auditor_copy");
        assertThat(cloned.getSystemPrompt()).isEqualTo("你是一名架构级代码审计专家...");
        assertThat(cloned.getToolsConfig()).isEqualTo("{\"bocha\":{\"enabled\":true}}");
        assertThat(cloned.getKnowledgeBaseIds()).containsExactly("kb-a", "kb-b");
        assertThat(cloned.getTemperature()).isEqualTo(0.2);
        assertThat(cloned.getAvatar()).isEqualTo("💻");
        assertThat(cloned.getCategory()).isEqualTo("代码研发");
        assertThat(cloned.getTags()).containsExactly("安全", "代码审计");
        assertThat(cloned.getCallCount()).isEqualTo(0L);
        assertThat(cloned.getAvgResponseTimeMs()).isEqualTo(0.0);
        assertThat(cloned.getApiKey()).isNull();
    }

    @Test
    void developerCannotUpdateOrDeleteSystemAgent() {
        Agent systemAgent = new Agent();
        systemAgent.setId("sys-1");
        systemAgent.setIsSystem(true);
        systemAgent.setOwnerUsername("system");
        when(agentRepository.findById("sys-1")).thenReturn(Optional.of(systemAgent));

        com.example.agentplatform.security.CurrentActor devActor =
                new com.example.agentplatform.security.CurrentActor("user-dev", "guass", com.example.agentplatform.model.UserRole.DEVELOPER);
        when(resourceAuthService.canManageAgent(eq(devActor), eq(systemAgent))).thenReturn(false);

        // Attempt update
        Agent patch = new Agent();
        patch.setName("Modified Name");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> {
            agentService.update("sys-1", patch, devActor);
        });

        // Attempt delete
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> {
            agentService.delete("sys-1", devActor);
        });
    }

    @Test
    void copyAgentByDeveloper_assignsDeveloperOwnership() {
        Agent systemAgent = new Agent();
        systemAgent.setId("sys-orig");
        systemAgent.setName("SQL优化专家");
        systemAgent.setCode("sql_expert");
        systemAgent.setIsSystem(true);

        when(agentRepository.findById("sys-orig")).thenReturn(Optional.of(systemAgent));
        when(agentRepository.existsByCode("sql_expert_copy")).thenReturn(false);
        when(agentRepository.save(any(Agent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.example.agentplatform.security.CurrentActor devActor =
                new com.example.agentplatform.security.CurrentActor("user-dev", "guass", com.example.agentplatform.model.UserRole.DEVELOPER);
        when(resourceAuthService.canCopyAgent(eq(devActor), eq(systemAgent))).thenReturn(true);

        Agent copy = agentService.copyAgent("sys-orig", devActor);

        assertThat(copy.getIsSystem()).isFalse();
        assertThat(copy.getOwnerId()).isEqualTo("user-dev");
        assertThat(copy.getOwnerUsername()).isEqualTo("guass");
    }

    @Test
    void dashboardStats_developerOnlyIncludesOwnedAgents() {
        Agent mine = new Agent();
        mine.setId("mine-1");
        mine.setName("我的客服");
        mine.setOwnerId("user-dev");
        mine.setCallCount(10L);
        mine.setStatus(AgentStatus.RUNNING);

        Agent system = new Agent();
        system.setId("sys-1");
        system.setName("平台公共助手");
        system.setIsSystem(true);
        system.setOwnerId("admin");
        system.setCallCount(999L);
        system.setStatus(AgentStatus.RUNNING);

        when(agentRepository.findAll()).thenReturn(List.of(mine, system));
        when(dailyStatRepository.findByStatDateBetween(any(), any())).thenReturn(List.of());
        when(conversationService.tokenUsageByModel(any(), any(), any(), any())).thenReturn(Map.of());
        when(conversationService.latestModelByAgent(any())).thenReturn(Map.of());

        com.example.agentplatform.security.CurrentActor devActor =
                new com.example.agentplatform.security.CurrentActor("user-dev", "guass", com.example.agentplatform.model.UserRole.DEVELOPER);
        com.example.agentplatform.model.DashboardStats asDev = agentService.getDashboardStats("7days", devActor);
        assertThat(asDev.getTotalAgents()).isEqualTo(1);
        assertThat(asDev.getTotalCalls()).isEqualTo(10L);

        com.example.agentplatform.security.CurrentActor adminActor =
                new com.example.agentplatform.security.CurrentActor("admin-1", "admin", com.example.agentplatform.model.UserRole.SUPER_ADMIN);
        com.example.agentplatform.model.DashboardStats asAdmin = agentService.getDashboardStats("7days", adminActor);
        assertThat(asAdmin.getTotalAgents()).isEqualTo(2);
        assertThat(asAdmin.getTotalCalls()).isEqualTo(1009L);
    }
}
