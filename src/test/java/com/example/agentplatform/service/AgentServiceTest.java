package com.example.agentplatform.service;

import com.example.agentplatform.config.ToolConfigSanitizer;
import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.repository.AgentDailyStatRepository;
import com.example.agentplatform.repository.AgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    private ToolConfigSanitizer toolConfigSanitizer = new ToolConfigSanitizer();

    private AgentService agentService;

    @BeforeEach
    void setUp() {
        agentService = new AgentService(
                agentRepository,
                dailyStatRepository,
                conversationService,
                toolConfigSanitizer,
                toolSecretService
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
        source.setApiKey("sk-agent-old-secret-key");
        source.setCallCount(999L);
        source.setAvgResponseTimeMs(450.5);
        source.setStatus(AgentStatus.RUNNING);

        when(agentRepository.findById("orig-id-123")).thenReturn(Optional.of(source));
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
        assertThat(cloned.getTemperature()).isEqualTo(0.2);
        assertThat(cloned.getAvatar()).isEqualTo("💻");
        assertThat(cloned.getCategory()).isEqualTo("代码研发");
        assertThat(cloned.getTags()).containsExactly("安全", "代码审计");
        assertThat(cloned.getCallCount()).isEqualTo(0L);
        assertThat(cloned.getAvgResponseTimeMs()).isEqualTo(0.0);
        assertThat(cloned.getApiKey()).isNull();

        verify(toolSecretService).copyForClonedAgent("orig-id-123", "cloned-new-id");
    }
}
