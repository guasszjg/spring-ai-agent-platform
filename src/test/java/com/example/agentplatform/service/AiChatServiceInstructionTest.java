package com.example.agentplatform.service;

import com.example.agentplatform.model.Agent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiChatServiceInstructionTest {

    @Mock
    private AgentService agentService;
    @Mock
    private AgentConversationService conversationService;
    @Mock
    private LlmGatewayService gatewayService;
    @Mock
    private OpenAiCompatibleClient openAiClient;
    @Mock
    private CustomHttpLlmClient customHttpClient;
    @Mock
    private com.example.agentplatform.tool.AgentToolRegistry toolRegistry;
    @Mock
    private AgentToolSecretService toolSecretService;
    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private ResourceAuthorizationService resourceAuthorizationService;

    @Test
    void testBuildEffectiveInstructionInjectsCurrentDateAndReplacesVariables() {
        when(knowledgeBaseService.buildRetrievalContext(any(), any())).thenReturn(null);

        AiChatService aiChatService = new AiChatService(
                agentService,
                conversationService,
                gatewayService,
                openAiClient,
                customHttpClient,
                toolRegistry,
                toolSecretService,
                null,
                knowledgeBaseService,
                resourceAuthorizationService,
                null,
                false
        );

        Agent agent = new Agent();
        agent.setId("agent-007");
        agent.setName("产品总监 PRD 智能助手");
        agent.setSystemPrompt("你是一名资深互联网产品总监。当前系统时间是 {{system_time}}。用户输入是 {{input}}。");

        String result = aiChatService.buildEffectiveInstruction(null, agent, "今天多少号？");

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        String expectedDate = now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));

        assertThat(result).contains("【系统基准环境】");
        assertThat(result).contains(expectedDate);
        assertThat(result).contains("Asia/Shanghai");
        assertThat(result).contains("【时效性准则】");
        assertThat(result).contains("切勿将历史网页的发布时间误认为当前的真实日期");
        assertThat(result).doesNotContain("{{system_time}}");
        assertThat(result).doesNotContain("{{input}}");
        assertThat(result).contains("用户输入是 今天多少号？");
    }

    @Test
    void testBuildEffectiveInstructionWithEnabledToolsInjectsToolBehaviorGuide() {
        when(knowledgeBaseService.buildRetrievalContext(any(), any())).thenReturn(null);

        AiChatService aiChatService = new AiChatService(
                agentService,
                conversationService,
                gatewayService,
                openAiClient,
                customHttpClient,
                toolRegistry,
                toolSecretService,
                null,
                knowledgeBaseService,
                resourceAuthorizationService,
                null,
                false
        );

        Agent agent = new Agent();
        agent.setId("agent-007");
        agent.setName("产品总监 PRD 智能助手");
        agent.setSystemPrompt("你是一名资深互联网产品总监。");

        String result = aiChatService.buildEffectiveInstruction(null, agent, "今天多少号？",
                java.util.List.of("获取当前时间", "星期几计算器", "联网检索"));

        assertThat(result).contains("【工具调用指引规范】");
        assertThat(result).contains("time_get_current_time");
        assertThat(result).contains("bocha_web_search");
        assertThat(result).contains("严禁使用联网搜索查询当前本地系统时间");
    }
}
