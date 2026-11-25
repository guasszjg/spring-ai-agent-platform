package com.shimeta.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.model.Agent;
import com.shimeta.model.AssistantAction;
import com.shimeta.model.UserRole;
import com.shimeta.repository.AssistantActionRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.AgentService;
import com.shimeta.service.ResourceAuthorizationService;
import com.shimeta.service.share.ShareLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI 助手的分享链接工具：查询只返回白名单字段，停用生成 B 级待确认卡片，确认后才真正停用 */
class AssistantShareLinkToolsTest {

    private final AgentService agentService = mock(AgentService.class);
    private final ResourceAuthorizationService auth = mock(ResourceAuthorizationService.class);
    private final ShareLinkService shareLinkService = mock(ShareLinkService.class);
    private final AssistantActionRepository actionRepository = mock(AssistantActionRepository.class);
    private final CurrentActor dev = new CurrentActor("u1", "dev", UserRole.DEVELOPER);
    private final ObjectMapper mapper = new ObjectMapper();
    private AssistantExtendedQueryTools queryTools;
    private AssistantExtendedWriteTools writeTools;
    private Agent agent;

    @BeforeEach
    void setUp() {
        agent = new Agent();
        agent.setId("a1");
        agent.setName("客服助手");
        when(agentService.getById(eq("a1"), any())).thenReturn(Optional.of(agent));
        when(auth.canManageAgent(any(), eq(agent))).thenReturn(true);
        when(actionRepository.save(any())).thenAnswer(inv -> {
            AssistantAction a = inv.getArgument(0);
            a.setId("act-0123456789ab");
            return a;
        });
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("id", "L1");
        link.put("name", "给 A 客户演示");
        link.put("path", "/chat/abc");
        link.put("code", "abc");
        link.put("accessMode", "ACCESS_CODE");
        link.put("status", "ACTIVE");
        link.put("state", "ACTIVE");
        link.put("visitorCount", 3);
        link.put("createdBy", "u1");
        when(shareLinkService.list("a1", dev)).thenReturn(List.of(link));

        AssistantQueryTools base = new AssistantQueryTools(agentService,
                mock(com.shimeta.service.KnowledgeBaseService.class), mock(com.shimeta.repository.KnowledgeBaseRepository.class),
                mock(com.shimeta.repository.KnowledgeIndexVersionRepository.class), mock(com.shimeta.service.LlmGatewayService.class),
                mock(com.shimeta.service.EmbeddingConfigService.class), mock(com.shimeta.service.DifyConfigService.class), auth,
                mock(com.shimeta.repository.AgentDailyStatRepository.class), mock(com.shimeta.service.AgentTemplateService.class),
                mock(PlatformDocsService.class));
        queryTools = new AssistantExtendedQueryTools(base, agentService, mock(com.shimeta.service.KnowledgeBaseService.class),
                mock(com.shimeta.service.AgentTemplateService.class), null, null, null, null, null, null, null, null, null, null);
        queryTools.setShareLinkService(shareLinkService);
        writeTools = new AssistantExtendedWriteTools(base, agentService, mock(com.shimeta.service.AgentTemplateService.class),
                mock(com.shimeta.service.KnowledgeBaseService.class), mock(com.shimeta.repository.KnowledgeBaseRepository.class), auth,
                mock(com.shimeta.service.LlmGatewayService.class), mock(com.shimeta.service.EmbeddingConfigService.class),
                mock(com.shimeta.service.OpenApiKeyService.class), actionRepository);
        writeTools.setShareLinkService(shareLinkService);
    }

    private AssistantTool tool(List<AssistantTool> tools, String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("查询分享链接：返回状态和用量，不返回链接码、访问码和创建人")
    @SuppressWarnings("unchecked")
    void listsLinksWithWhitelistedFields() throws Exception {
        Map<String, Object> res = tool(queryTools.tools(), AssistantExtendedQueryTools.LIST_SHARE_LINKS).handler()
                .handle(mapper.readTree("{\"agent\":\"a1\"}"), ToolContext.of(dev, "conv"));

        assertThat(res.get("ok")).isEqualTo(true);
        Map<String, Object> item = ((List<Map<String, Object>>) res.get("links")).get(0);
        assertThat(item).containsEntry("state", "可用").containsEntry("accessMode", "访问码").containsEntry("enabled", true);
        assertThat(item).doesNotContainKeys("code", "accessCode", "createdBy");
    }

    @Test
    @DisplayName("停用：生成 B 级待确认卡片，不直接停用；确认后才调用停用")
    void disableCreatesCardThenExecutes() throws Exception {
        Map<String, Object> res = tool(writeTools.tools(), AssistantExtendedWriteTools.DISABLE_SHARE_LINK).handler()
                .handle(mapper.readTree("{\"agent\":\"a1\",\"link\":\"演示\"}"), ToolContext.of(dev, "conv"));

        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("riskLevel")).isEqualTo("B");
        verify(shareLinkService, never()).setEnabled(any(), any(), Mockito.anyBoolean(), any());

        writeTools.execute(AssistantExtendedWriteTools.DISABLE_SHARE_LINK,
                "{\"agentId\":\"a1\",\"linkId\":\"L1\",\"linkName\":\"给 A 客户演示\"}", null, dev);
        verify(shareLinkService).setEnabled("a1", "L1", false, dev);
    }

    @Test
    @DisplayName("找不到链接时不生成卡片，并列出现有链接名称")
    void unknownLinkIsReported() throws Exception {
        Map<String, Object> res = tool(writeTools.tools(), AssistantExtendedWriteTools.DISABLE_SHARE_LINK).handler()
                .handle(mapper.readTree("{\"agent\":\"a1\",\"link\":\"不存在的\"}"), ToolContext.of(dev, "conv"));

        assertThat(res.get("ok")).isEqualTo(false);
        assertThat(res.get("available")).isEqualTo(List.of("给 A 客户演示"));
        verify(actionRepository, never()).save(any());
    }
}
