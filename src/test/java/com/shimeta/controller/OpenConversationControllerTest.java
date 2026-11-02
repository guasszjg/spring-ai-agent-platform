package com.shimeta.controller;

import com.shimeta.model.Agent;
import com.shimeta.model.AgentConversation;
import com.shimeta.model.ApiResponse;
import com.shimeta.model.AppUser;
import com.shimeta.model.OpenApiKey;
import com.shimeta.repository.AgentConversationMessageRepository;
import com.shimeta.repository.AgentConversationRepository;
import com.shimeta.repository.AgentRepository;
import com.shimeta.security.OpenApiContext;
import com.shimeta.service.ResourceAuthorizationService;
import com.shimeta.service.UsageRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 开放接口会话：只能看到凭证可访问的智能体的会话，不能跨租户、越出凭证的智能体范围 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenConversationControllerTest {

    @Mock AgentConversationRepository conversationRepository;
    @Mock AgentConversationMessageRepository messageRepository;
    @Mock AgentRepository agentRepository;
    @Mock ResourceAuthorizationService authorizationService;
    @Mock UsageRecorder usageRecorder;

    private OpenConversationController controller;
    private final OpenApiKey key = new OpenApiKey();
    private final Agent mine = agent("agent-mine");
    private final Agent mineOutOfScope = agent("agent-mine-2");
    private final Agent others = agent("agent-others");

    private static Agent agent(String id) {
        Agent a = new Agent();
        a.setId(id);
        return a;
    }

    private static AgentConversation conv(String id, String agentId) {
        AgentConversation c = new AgentConversation();
        c.setId(id);
        c.setAgentId(agentId);
        return c;
    }

    @BeforeEach
    void setUp() {
        controller = new OpenConversationController(conversationRepository, messageRepository, agentRepository,
                authorizationService, usageRecorder);
        key.setId("oak-1");
        key.setScopes(List.of("conversations:read", "chat"));
        key.setAgentScope(List.of("agent-mine"));
        AppUser owner = new AppUser();
        owner.setId("u-dev");
        OpenApiContext.set(new OpenApiContext("req-1", key, owner, "127.0.0.1"));
        when(agentRepository.findAll()).thenReturn(List.of(mine, mineOutOfScope, others));
        when(agentRepository.findById(any())).thenAnswer(inv -> List.of(mine, mineOutOfScope, others).stream()
                .filter(a -> a.getId().equals(inv.getArgument(0))).findFirst());
        // 开发者能看到自己的两个智能体，看不到别人的
        when(authorizationService.canViewAgent(any(), eq(mine))).thenReturn(true);
        when(authorizationService.canViewAgent(any(), eq(mineOutOfScope))).thenReturn(true);
        when(authorizationService.canViewAgent(any(), eq(others))).thenReturn(false);
        when(authorizationService.canManageAgent(any(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        OpenApiContext.clear();
    }

    @Test
    @DisplayName("不带 agent_id 时只查凭证范围内、开发者可见的智能体的会话，不再返回全部租户的会话")
    void listWithoutAgentIdIsScoped() {
        when(conversationRepository.findByAgentIdInOrderByUpdatedAtDesc(argThat(ids -> ids.size() == 1 && ids.contains("agent-mine"))))
                .thenReturn(List.of(conv("c1", "agent-mine")));

        ResponseEntity<?> res = controller.listConversations(null, null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) ((ApiResponse<?>) res.getBody()).getData()).hasSize(1);
        verify(conversationRepository, never()).findAll();
    }

    @Test
    @DisplayName("指定凭证范围外的智能体（即使是自己的）也被拒绝")
    void listRejectsAgentOutsideKeyScope() {
        assertThat(controller.listConversations("agent-mine-2", null).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.listConversations("agent-others", null).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("别的租户的会话、智能体已删除的会话，消息与删除接口都拒绝")
    void messagesAndDeleteRejectForeignAndOrphanConversations() {
        when(conversationRepository.findById("c-others")).thenReturn(Optional.of(conv("c-others", "agent-others")));
        when(conversationRepository.findById("c-orphan")).thenReturn(Optional.of(conv("c-orphan", "agent-deleted")));
        when(conversationRepository.findById("c-mine")).thenReturn(Optional.of(conv("c-mine", "agent-mine")));

        assertThat(controller.getMessages("c-others").getStatusCode().value()).isEqualTo(403);
        assertThat(controller.getMessages("c-orphan").getStatusCode().value()).isEqualTo(403);
        assertThat(controller.deleteConversation("c-others").getStatusCode().value()).isEqualTo(403);
        assertThat(controller.deleteConversation("c-orphan").getStatusCode().value()).isEqualTo(403);
        verify(messageRepository, never()).deleteByConversationId(any());

        assertThat(controller.getMessages("c-mine").getStatusCode().value()).isEqualTo(200);
    }
}
