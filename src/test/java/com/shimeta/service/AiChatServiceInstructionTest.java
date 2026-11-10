package com.shimeta.service;

import com.shimeta.model.Agent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private com.shimeta.tool.AgentToolRegistry toolRegistry;
    @Mock
    private AgentToolSecretService toolSecretService;
    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private ResourceAuthorizationService resourceAuthorizationService;

    @Test
    void testBuildEffectiveInstructionInjectsCurrentDateAndReplacesVariables() {
        when(knowledgeBaseService.buildRetrieval(any(), any())).thenReturn(new KnowledgeBaseService.RetrievalContext("", java.util.List.of()));

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

        AiChatService.PromptLayout layout = aiChatService.buildPromptLayout(null, agent, "今天多少号？", null);
        String result = layout.system();

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        String expectedDate = now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));

        assertThat(result).contains("【系统基准环境】");
        assertThat(result).doesNotContain("【当前时间】" + expectedDate);
        assertThat(layout.user()).startsWith("【当前时间】" + expectedDate);
        assertThat(layout.user()).endsWith("【用户问题】\n今天多少号？");
        assertThat(result).contains("Asia/Shanghai");
        assertThat(result).contains("【时效性准则】");
        assertThat(result).contains("切勿将历史网页的发布时间误认为当前的真实日期");
        assertThat(result).doesNotContain("{{system_time}}");
        assertThat(result).doesNotContain("{{input}}");
        assertThat(result).contains("用户输入是 今天多少号？");
    }

    @Test
    void testBuildEffectiveInstructionWithEnabledToolsInjectsToolBehaviorGuide() {
        when(knowledgeBaseService.buildRetrieval(any(), any())).thenReturn(new KnowledgeBaseService.RetrievalContext("", java.util.List.of()));

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

        String result = aiChatService.buildPromptLayout(null, agent, "今天多少号？",
                java.util.List.of("获取当前时间", "星期几计算器", "联网检索")).system();

        assertThat(result).contains("【工具调用指引规范】");
        assertThat(result).contains("time_get_current_time");
        assertThat(result).contains("bocha_web_search");
        assertThat(result).contains("严禁使用联网搜索查询当前本地系统时间");
    }

    private AiChatService service() {
        return new AiChatService(agentService, conversationService, gatewayService, openAiClient, customHttpClient,
                toolRegistry, toolSecretService, null, knowledgeBaseService, resourceAuthorizationService, null, false);
    }

    private Agent agentWithPrompt(String prompt) {
        Agent agent = new Agent();
        agent.setId("agent-007");
        agent.setName("客服");
        agent.setSystemPrompt(prompt);
        return agent;
    }

    @Test
    void systemStaysFixedWhileTimeAndKnowledgeGoIntoUserMessage() throws Exception {
        when(knowledgeBaseService.buildRetrieval(any(), eq("问题一")))
                .thenReturn(new KnowledgeBaseService.RetrievalContext("【知识库检索结果】资料一", java.util.List.of()));
        when(knowledgeBaseService.buildRetrieval(any(), eq("问题二")))
                .thenReturn(new KnowledgeBaseService.RetrievalContext("【知识库检索结果】资料二", java.util.List.of()));
        AiChatService service = service();
        Agent agent = agentWithPrompt("你是客服。");

        AiChatService.PromptLayout first = service.buildPromptLayout(null, agent, "问题一", java.util.List.of("联网检索"));
        Thread.sleep(1100); // 跨过一秒，确认时间变化不影响 system
        AiChatService.PromptLayout second = service.buildPromptLayout(null, agent, "问题二", java.util.List.of("联网检索"));

        assertThat(second.system()).isEqualTo(first.system());
        assertThat(first.system()).contains("你是客服。").doesNotContain("资料一");
        assertThat(first.user()).contains("【知识库检索结果】资料一").endsWith("【用户问题】\n问题一");
        assertThat(second.user()).contains("资料二").doesNotContain("资料一");
        assertThat(second.user()).isNotEqualTo(first.user());
    }

    @Test
    void historyStaysBeforeTheNewUserMessage() {
        when(knowledgeBaseService.buildRetrieval(any(), any()))
                .thenReturn(new KnowledgeBaseService.RetrievalContext("【知识库检索结果】资料", java.util.List.of()));
        AiChatService.PromptLayout layout = service().buildPromptLayout(null, agentWithPrompt("你是客服。"), "新问题", null);
        com.shimeta.model.ChatMessage old = new com.shimeta.model.ChatMessage();
        old.setRole("user");
        old.setContent("旧问题");

        var messages = OpenAiCompatibleClient.toMessages(layout.system(), layout.user(), java.util.List.of(old));

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).get("role")).isEqualTo("system");
        assertThat(messages.get(1).get("content")).isEqualTo("旧问题");
        assertThat(String.valueOf(messages.get(2).get("content"))).contains("资料").endsWith("新问题");
    }

    @Test
    void userPlaceholderInPromptIsStillSupported() {
        when(knowledgeBaseService.buildRetrieval(any(), any()))
                .thenReturn(new KnowledgeBaseService.RetrievalContext("", java.util.List.of()));
        AiChatService.PromptLayout layout = service().buildPromptLayout(null, agentWithPrompt("时间 {{sys.date}}，输入 {{input}}"), "你好", null);

        assertThat(layout.system()).doesNotContain("{{sys.date}}").contains("输入 你好");
        assertThat(layout.user()).doesNotContain("【知识库检索结果】");
    }

    @Test
    void skillCatalogGoesAtTheEndOfSystemPrompt() {
        when(knowledgeBaseService.buildRetrieval(any(), any()))
                .thenReturn(new KnowledgeBaseService.RetrievalContext("", java.util.List.of()));
        String catalog = new com.shimeta.skill.SkillSession(java.util.List.of(
                new com.shimeta.skill.SkillSpec("s1", "退款", "用户要求退款时", "完整步骤正文", java.util.List.of())),
                java.util.Set.of(), null).catalog();
        AiChatService.PromptLayout layout = service().buildPromptLayout(null, agentWithPrompt("你是客服。"), "我要退款", null, null, catalog);

        assertThat(layout.system()).contains("你是客服。").endsWith(catalog).doesNotContain("完整步骤正文");
        assertThat(layout.user()).doesNotContain("可用技能");
    }
}
