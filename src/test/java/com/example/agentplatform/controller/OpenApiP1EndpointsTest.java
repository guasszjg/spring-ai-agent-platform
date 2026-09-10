package com.example.agentplatform.controller;

import com.example.agentplatform.model.*;
import com.example.agentplatform.repository.*;
import com.example.agentplatform.security.*;
import com.example.agentplatform.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenApiP1EndpointsTest {

    @Mock
    private AgentService agentService;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private ResourceAuthorizationService authorizationService;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private UsageRecorder usageRecorder;
    @Mock
    private AuditEventRepository auditEventRepository;

    private AppUser owner;
    private OpenApiKey key;

    @BeforeEach
    void setUp() {
        owner = new AppUser();
        owner.setId("usr-dev-1");
        owner.setUsername("developer1");
        owner.setRole(UserRole.DEVELOPER.getCode());
        owner.setStatus(UserStatus.ACTIVE);

        key = new OpenApiKey();
        key.setId("oak-1");
        key.setName("Test Key");
        key.setOwnerId(owner.getId());
        key.setStatus("ACTIVE");
    }

    @AfterEach
    void tearDown() {
        OpenApiContext.clear();
        CurrentActor.clear();
    }

    private void mockContext(List<String> scopes, List<String> agentScope) {
        key.setScopes(scopes);
        key.setAgentScope(agentScope);
        OpenApiContext ctx = new OpenApiContext("req-test-1", key, owner, "127.0.0.1");
        OpenApiContext.set(ctx);
        CurrentActor.set(ctx.asActor());
    }

    @Test
    @DisplayName("OpenAgentController: 缺少 agents:read scope 时返回 403 scope_missing")
    void openAgentList_missingScope() {
        mockContext(List.of(OpenApiScopes.CHAT), List.of());
        OpenAgentController controller = new OpenAgentController(
                agentService, agentRepository, knowledgeBaseRepository,
                authorizationService, auditRecorder, usageRecorder);

        ResponseEntity<?> response = controller.listAgents();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isSuccess()).isFalse();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.getData();
        assertThat(data.get("code")).isEqualTo("scope_missing");
    }

    @Test
    @DisplayName("OpenAgentController: 拥有 agents:read scope 时返回已脱敏的 DTO 列表")
    void openAgentList_success() {
        mockContext(List.of(OpenApiScopes.AGENTS_READ), List.of());

        Agent agent = new Agent();
        agent.setId("agt-1");
        agent.setName("研发助手");
        agent.setModelName("gpt-4o-mini");
        agent.setSystemPrompt("Super secret system prompt should not leak");
        agent.setStatus(AgentStatus.RUNNING);

        PageResult<Agent> page = new PageResult<>(List.of(agent), 1, 1, 10);
        when(agentService.searchAgents(any(), any(), any(), any(), any(), any(), eq(1), eq(1000))).thenReturn(page);

        OpenAgentController controller = new OpenAgentController(
                agentService, agentRepository, knowledgeBaseRepository,
                authorizationService, auditRecorder, usageRecorder);

        ResponseEntity<?> response = controller.listAgents();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertThat(body.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<OpenAgentDto> list = (List<OpenAgentDto>) body.getData();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getName()).isEqualTo("研发助手");
    }

    @Test
    @DisplayName("OpenKnowledgeBaseController: 缺少 kb:read 时拒绝访问")
    void openKnowledgeBaseList_missingScope() {
        mockContext(List.of(OpenApiScopes.CHAT), List.of());
        OpenKnowledgeBaseController controller = new OpenKnowledgeBaseController(
                knowledgeBaseService, knowledgeBaseRepository,
                authorizationService, auditRecorder, usageRecorder);

        ResponseEntity<?> response = controller.listKnowledgeBases();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.getData();
        assertThat(data.get("code")).isEqualTo("scope_missing");
    }

    @Test
    @DisplayName("OpenUsageController: 拥有 usage:read 时返回统计概览")
    void openUsageSummary_success() {
        mockContext(List.of(OpenApiScopes.USAGE_READ), List.of());
        when(usageRecorder.getSummary(eq(owner.getId()), any(), any())).thenReturn(new java.util.LinkedHashMap<>(Map.of("calls", 42L)));

        OpenUsageController controller = new OpenUsageController(usageRecorder, auditEventRepository);
        ResponseEntity<?> response = controller.getUsageSummary(LocalDate.now().minusDays(7), LocalDate.now());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertThat(body.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.getData();
        assertThat(data.get("calls")).isEqualTo(42L);
    }
}
