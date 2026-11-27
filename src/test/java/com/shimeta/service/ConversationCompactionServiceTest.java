package com.shimeta.service;

import com.shimeta.model.AgentConversation;
import com.shimeta.model.AgentConversationMessage;
import com.shimeta.model.AssistantConversation;
import com.shimeta.model.AssistantMessage;
import com.shimeta.model.LlmProvider;
import com.shimeta.repository.AgentConversationMessageRepository;
import com.shimeta.repository.AgentConversationRepository;
import com.shimeta.repository.AssistantConversationRepository;
import com.shimeta.repository.AssistantMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationCompactionServiceTest {

    @Mock LlmGatewayService gatewayService;
    @Mock OpenAiCompatibleClient openAiClient;
    @Mock AssistantConversationRepository assistantConversationRepository;
    @Mock AssistantMessageRepository assistantMessageRepository;
    @Mock AgentConversationRepository agentConversationRepository;
    @Mock AgentConversationMessageRepository agentMessageRepository;

    private ConversationCompactionService service;

    @BeforeEach
    void setUp() {
        service = new ConversationCompactionService(
                gatewayService, openAiClient,
                assistantConversationRepository, assistantMessageRepository,
                agentConversationRepository, agentMessageRepository);
    }

    @Test
    void assistantCompactionSkipsShortConversationsAndCompactsOverflowWithLlm() {
        AssistantConversation conv = new AssistantConversation();
        conv.setId("asc-1");
        conv.setSummaryMsgCount(0);
        when(assistantConversationRepository.findById("asc-1")).thenReturn(Optional.of(conv));

        // 1. 短对话（6 条 <= 保留滑窗 8 条）：不触发压缩，零大模型调用
        List<AssistantMessage> shortList = buildAssistantMessages(6);
        when(assistantMessageRepository.findByConversationIdOrderByCreatedAtAsc("asc-1")).thenReturn(shortList);
        var shortRes = service.compactAssistantInternal("asc-1", ConversationCompactionService.ASSISTANT_KEEP_RECENT,
                ConversationCompactionService.MIN_BATCH_MESSAGES);
        assertFalse(shortRes.compacted());
        verifyNoInteractions(openAiClient);

        // 2. 长对话（12 条：超出 8 条正好 4 条达到批量阈值）：调用大模型合并浓缩并持久化
        List<AssistantMessage> longList = buildAssistantMessages(12);
        when(assistantMessageRepository.findByConversationIdOrderByCreatedAtAsc("asc-1")).thenReturn(longList);
        LlmProvider provider = new LlmProvider();
        provider.setBaseUrl("https://api.deepseek.com");
        provider.setDefaultModel("deepseek-chat");
        when(gatewayService.resolveAssistantRoute()).thenReturn(Optional.of(new LlmGatewayService.AssistantRoute(
                new LlmGatewayService.ResolvedRoute(provider, null, 30000, 0, "sk-test", null), null, false)));
        when(openAiClient.chatWithTools(anyString(), anyString(), anyString(), any(), any(), isNull(), isNull(), any(), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.ChatResult("用户排查了售后智能体 agent-9，确认是向量模型未激活。", 120, 30, 150));

        var res = service.compactAssistantInternal("asc-1", ConversationCompactionService.ASSISTANT_KEEP_RECENT,
                ConversationCompactionService.MIN_BATCH_MESSAGES);
        assertTrue(res.compacted());
        assertEquals(4, res.summaryMsgCount());
        assertTrue(res.memorySummary().contains("agent-9"));
        assertEquals(4, conv.getSummaryMsgCount());
        verify(assistantConversationRepository).save(conv);
    }

    @Test
    void agentCompactionFallsBackToRuleSummaryWhenLlmUnavailable() {
        AgentConversation conv = new AgentConversation();
        conv.setId("conv-1");
        conv.setAgentId("agent-1");
        conv.setSummaryMsgCount(0);
        when(agentConversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

        List<AgentConversationMessage> msgs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            AgentConversationMessage m = new AgentConversationMessage();
            m.setRole(i % 2 == 0 ? "user" : "assistant");
            m.setContent((i % 2 == 0 ? "设备 SN-8801 报错第 " : "已建议重启检查第 ") + i + " 步");
            msgs.add(m);
        }
        when(agentMessageRepository.findByConversationIdOrderByCreatedAtAsc("conv-1")).thenReturn(msgs);
        when(gatewayService.resolveAssistantRoute()).thenReturn(Optional.empty());
        when(gatewayService.resolveRoute(any())).thenReturn(Optional.empty());

        // keepRecent = 6（3 轮），10 - 6 = 4 条溢出，走规则兜底摘要
        var res = service.compactAgentInternal("conv-1", 6, null, null);
        assertTrue(res.compacted());
        assertEquals(4, res.summaryMsgCount());
        assertNotNull(res.memorySummary());
        assertTrue(res.memorySummary().contains("SN-8801"));
        verify(agentConversationRepository).save(conv);
    }

    private static List<AssistantMessage> buildAssistantMessages(int count) {
        List<AssistantMessage> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            AssistantMessage m = new AssistantMessage();
            m.setRole(i % 2 == 0 ? AssistantMessage.ROLE_USER : AssistantMessage.ROLE_ASSISTANT);
            m.setContent("消息内容 #" + i);
            list.add(m);
        }
        return list;
    }
}
