package com.shimeta.service;

import com.shimeta.model.Agent;
import com.shimeta.model.AgentConversation;
import com.shimeta.model.ChatGeneration;
import com.shimeta.model.ChatMessage;
import com.shimeta.model.ChatRequest;
import com.shimeta.model.LlmProtocolType;
import com.shimeta.model.LlmProvider;
import com.shimeta.model.LlmProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 模型通道能力：不支持工具调用时不发工具定义；配置了上下文窗口时按窗口收缩知识库和历史 */
class AiChatServiceChannelCapabilityTest {

    private final AgentService agentService = mock(AgentService.class);
    private final AgentConversationService conversationService = mock(AgentConversationService.class);
    private final LlmGatewayService gatewayService = mock(LlmGatewayService.class);
    private final OpenAiCompatibleClient openAiClient = mock(OpenAiCompatibleClient.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final com.shimeta.tool.AgentToolRegistry toolRegistry = mock(com.shimeta.tool.AgentToolRegistry.class);
    private final LlmProvider primary = new LlmProvider();
    private final Agent agent = new Agent();
    private AiChatService service;

    @BeforeEach
    void setUp() {
        com.shimeta.security.CurrentActor.clear();
        primary.setId("p1");
        primary.setName("板卡小模型");
        primary.setVendor(LlmProviderType.CUSTOM);
        primary.setProtocol(LlmProtocolType.OPENAI);
        primary.setBaseUrl("http://model");
        primary.setDefaultModel("small");
        agent.setId("a1");
        agent.setName("客服");
        agent.setKnowledgeBaseIds(List.of("kb1"));
        agent.setMemoryTurns(5);
        when(agentService.getById("a1")).thenReturn(Optional.of(agent));
        when(knowledgeBaseService.buildRetrieval(any(), any()))
                .thenReturn(new KnowledgeBaseService.RetrievalContext("【知识库检索结果】默认", List.of()));
        when(knowledgeBaseService.buildRetrieval(any(), any(), any()))
                .thenReturn(new KnowledgeBaseService.RetrievalContext("【知识库检索结果】收缩", List.of()));
        when(gatewayService.resolveRoute(any())).thenReturn(Optional.of(
                new LlmGatewayService.ResolvedRoute(primary, null, 1000, 0, "k1", null)));
        when(toolRegistry.getToolDefinitions(any())).thenReturn(List.of(
                Map.of("type", "function", "function", Map.of("name", "time_get_current_time"))));
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        when(conversationService.appendTurn(any(), any(), any(), any(), any(), any(), anyLong(), anyInt(), any()))
                .thenReturn(conversation);
        when(openAiClient.chatWithTools(anyString(), any(), anyString(), any(), anyBoolean(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.ChatResult("好的", 10, 2, 12));
        service = new AiChatService(agentService, conversationService, gatewayService, openAiClient,
                mock(CustomHttpLlmClient.class), toolRegistry, mock(AgentToolSecretService.class),
                null, knowledgeBaseService, mock(ResourceAuthorizationService.class), null, false);
    }

    private ChatRequest request(List<ChatMessage> history) {
        ChatRequest request = new ChatRequest();
        request.setAgentId("a1");
        request.setMessage("你好");
        request.setEnabledTools(List.of("time_get_current_time"));
        request.setHistory(history);
        return request;
    }

    @SuppressWarnings("unchecked")
    private Call capture() {
        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Map<String, Object>>> tools = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ChatGeneration> generation = ArgumentCaptor.forClass(ChatGeneration.class);
        verify(openAiClient).chatWithTools(anyString(), any(), anyString(), any(), anyBoolean(),
                messages.capture(), tools.capture(), any(), generation.capture(), anyInt(), anyInt());
        return new Call(messages.getValue(), tools.getValue(), generation.getValue());
    }

    private record Call(List<Map<String, Object>> messages, List<Map<String, Object>> tools, ChatGeneration generation) {
        String allContent() {
            return messages.stream().map(m -> String.valueOf(m.get("content"))).reduce("", String::concat);
        }
    }

    @Test
    @DisplayName("通道关闭了工具调用：不发工具定义，提示词里也没有工具指引")
    void channelWithoutToolsSkipsTools() {
        primary.setSupportsTools(false);

        service.chat(request(null));

        Call call = capture();
        assertThat(call.tools()).isEmpty();
        assertThat(call.allContent()).doesNotContain("工具调用指引");
    }

    @Test
    @DisplayName("升级前的通道没设置过：照常发送工具定义")
    void unsetFlagKeepsSendingTools() {
        service.chat(request(null));

        assertThat(capture().tools()).hasSize(1);
    }

    @Test
    @DisplayName("没配置上下文窗口：知识库用默认上限，历史全带，max_tokens 用智能体设置")
    void noWindowKeepsOldBehavior() {
        List<ChatMessage> history = List.of(new ChatMessage("user", "上一问"), new ChatMessage("assistant", "上一答"));

        service.chat(request(history));

        Call call = capture();
        assertThat(call.messages()).hasSize(4);
        assertThat(call.allContent()).contains("默认");
        assertThat(call.generation().getMaxTokens()).isEqualTo(2048);
        verify(knowledgeBaseService, never()).buildRetrieval(any(), any(), any());
    }

    @Test
    @DisplayName("配置了 4096 窗口：知识库按剩余空间检索，放不下的旧历史丢掉，max_tokens 收到窗口四分之一")
    void windowShrinksKnowledgeAndHistory() {
        primary.setContextWindow(4096);
        String longText = "数".repeat(1500);
        List<ChatMessage> history = new ArrayList<>(List.of(
                new ChatMessage("user", longText), new ChatMessage("assistant", longText),
                new ChatMessage("user", "上一问"), new ChatMessage("assistant", "上一答")));

        service.chat(request(history));

        Call call = capture();
        ArgumentCaptor<Integer> budget = ArgumentCaptor.forClass(Integer.class);
        verify(knowledgeBaseService).buildRetrieval(any(), eq("你好"), budget.capture());
        assertThat(budget.getValue()).isBetween(ContextWindowPlanner.MIN_KNOWLEDGE_TOKENS, 3000);
        assertThat(call.allContent()).contains("收缩").doesNotContain(longText);
        assertThat(call.allContent()).contains("上一问");
        assertThat(call.generation().getMaxTokens()).isEqualTo(1024);
    }

    @Test
    @DisplayName("系统提示词已经占满窗口：不再检索知识库")
    void hugeSystemPromptSkipsRetrieval() {
        primary.setContextWindow(1024);
        agent.setSystemPrompt("规".repeat(1000));

        service.chat(request(null));

        verify(knowledgeBaseService, never()).buildRetrieval(any(), any(), any());
        verify(knowledgeBaseService, never()).buildRetrieval(any(), any());
    }
}
