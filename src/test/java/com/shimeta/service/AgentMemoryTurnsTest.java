package com.shimeta.service;

import com.shimeta.model.Agent;
import com.shimeta.model.AgentConversation;
import com.shimeta.model.AgentConversationMessage;
import com.shimeta.model.ChatMessage;
import com.shimeta.repository.AgentConversationMessageRepository;
import com.shimeta.repository.AgentConversationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 智能体上下文记忆：轮数设置、历史裁剪、开放接口按会话取历史 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentMemoryTurnsTest {

    @Mock AgentConversationRepository conversationRepository;
    @Mock AgentConversationMessageRepository messageRepository;

    private static ChatMessage user(String text) {
        return new ChatMessage("user", text);
    }

    private static ChatMessage bot(String text) {
        return new ChatMessage("assistant", text);
    }

    private static List<String> contents(List<ChatMessage> messages) {
        return messages.stream().map(ChatMessage::getContent).toList();
    }

    @Test
    @DisplayName("记忆轮数：未设置用默认 3 轮，超出范围截到 0～10")
    void resolveMemoryTurns() {
        Agent agent = new Agent();
        assertThat(agent.resolveMemoryTurns()).isEqualTo(3);
        agent.setMemoryTurns(null);
        assertThat(agent.resolveMemoryTurns()).isEqualTo(3);
        agent.setMemoryTurns(0);
        assertThat(agent.resolveMemoryTurns()).isZero();
        agent.setMemoryTurns(-2);
        assertThat(agent.resolveMemoryTurns()).isZero();
        agent.setMemoryTurns(50);
        assertThat(agent.resolveMemoryTurns()).isEqualTo(10);
    }

    @Test
    @DisplayName("只保留最近 N 轮；0 轮时不带任何历史")
    void keepsLatestTurns() {
        List<ChatMessage> history = List.of(user("q1"), bot("a1"), user("q2"), bot("a2"), user("q3"), bot("a3"));

        assertThat(contents(AiChatService.recentTurns(history, 2, "q4"))).containsExactly("q2", "a2", "q3", "a3");
        assertThat(contents(AiChatService.recentTurns(history, 10, "q4"))).containsExactly("q1", "a1", "q2", "a2", "q3", "a3");
        assertThat(AiChatService.recentTurns(history, 0, "q4")).isEmpty();
        assertThat(AiChatService.recentTurns(null, 3, "q4")).isEmpty();
    }

    @Test
    @DisplayName("调用方把本次问题也放进历史末尾时去掉，模型不会看到两遍")
    void dropsCurrentQuestionFromHistory() {
        List<ChatMessage> history = List.of(user("q1"), bot("a1"), user("q2"), bot("a2"), user(" 本次问题 "));

        assertThat(contents(AiChatService.recentTurns(history, 1, "本次问题"))).containsExactly("q2", "a2");
        assertThat(contents(AiChatService.recentTurns(history, 3, "本次问题"))).containsExactly("q1", "a1", "q2", "a2");
    }

    @Test
    @DisplayName("裁剪结果不以助手消息开头，空消息被忽略")
    void startsWithUserMessage() {
        List<ChatMessage> history = new java.util.ArrayList<>(List.of(bot("欢迎语"), user("q1"), bot("a1")));
        history.add(null);
        history.add(new ChatMessage("user", null));

        assertThat(contents(AiChatService.recentTurns(history, 2, "q2"))).containsExactly("q1", "a1");
    }

    private AgentConversation conversation(String agentId, String account) {
        AgentConversation c = new AgentConversation();
        c.setId("conv-1");
        c.setAgentId(agentId);
        c.setAccount(account);
        return c;
    }

    private AgentConversationMessage stored(String role, String content) {
        AgentConversationMessage m = new AgentConversationMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    @DisplayName("开放接口：按会话取最近 N 轮，按时间正序返回")
    void openApiLoadsRecentTurns() {
        AgentConversationService service = new AgentConversationService(conversationRepository, messageRepository);
        when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conversation("agent-1", "device-01")));
        // 仓库按时间倒序返回
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(eq("conv-1"), any()))
                .thenReturn(List.of(stored("assistant", "a2"), stored("user", "q2"), stored("assistant", "a1"), stored("user", "q1")));

        List<ChatMessage> history = service.recentHistory("agent-1", "conv-1", "device-01", 2);

        assertThat(contents(history)).containsExactly("q1", "a1", "q2", "a2");
        verify(messageRepository).findByConversationIdOrderByCreatedAtDesc("conv-1", PageRequest.of(0, 4));
    }

    @Test
    @DisplayName("开放接口：别的智能体或别的调用方的会话、关闭记忆、没带会话 ID 时都不取历史")
    void openApiRejectsForeignConversations() {
        AgentConversationService service = new AgentConversationService(conversationRepository, messageRepository);
        when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conversation("agent-1", "device-01")));

        assertThat(service.recentHistory("agent-2", "conv-1", "device-01", 3)).isEmpty();
        assertThat(service.recentHistory("agent-1", "conv-1", "device-02", 3)).isEmpty();
        assertThat(service.recentHistory("agent-1", "conv-1", null, 3)).isEmpty();
        assertThat(service.recentHistory("agent-1", "conv-1", "device-01", 0)).isEmpty();
        assertThat(service.recentHistory("agent-1", null, "device-01", 3)).isEmpty();
        verify(messageRepository, never()).findByConversationIdOrderByCreatedAtDesc(any(), any());
    }
}
