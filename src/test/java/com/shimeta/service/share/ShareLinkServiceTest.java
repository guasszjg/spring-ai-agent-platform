package com.shimeta.service.share;

import com.shimeta.model.Agent;
import com.shimeta.model.AgentShareLink;
import com.shimeta.model.AgentStatus;
import com.shimeta.model.OpenApiKey;
import com.shimeta.model.UserRole;
import com.shimeta.repository.AgentConversationRepository;
import com.shimeta.repository.AgentRepository;
import com.shimeta.repository.AgentShareLinkRepository;
import com.shimeta.repository.AgentShareVisitorRepository;
import com.shimeta.repository.UserRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.AgentConversationService;
import com.shimeta.service.AuditRecorder;
import com.shimeta.service.OpenApiKeyService;
import com.shimeta.service.PlatformSettingService;
import com.shimeta.service.ResourceAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShareLinkServiceTest {

    private final AgentShareLinkRepository linkRepository = mock(AgentShareLinkRepository.class);
    private final AgentShareVisitorRepository visitorRepository = mock(AgentShareVisitorRepository.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ResourceAuthorizationService auth = mock(ResourceAuthorizationService.class);
    private final OpenApiKeyService keyService = mock(OpenApiKeyService.class);
    private final PlatformSettingService settings = mock(PlatformSettingService.class);
    private final Map<String, AgentShareLink> saved = new HashMap<>();
    private final CurrentActor dev = new CurrentActor("u1", "dev", UserRole.DEVELOPER);
    private ShareLinkService service;
    private Agent agent;

    @BeforeEach
    void setUp() {
        agent = new Agent();
        agent.setId("a1");
        agent.setName("客服助手");
        agent.setStatus(AgentStatus.RUNNING);
        agent.setOwnerId("u1");
        when(agentRepository.findById("a1")).thenReturn(Optional.of(agent));
        when(auth.canManageAgent(any(), eq(agent))).thenReturn(true);
        when(linkRepository.save(any())).thenAnswer(inv -> {
            AgentShareLink l = inv.getArgument(0);
            saved.put(l.getId(), l);
            return l;
        });
        when(linkRepository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(saved.get(inv.<String>getArgument(0))));
        OpenApiKey key = new OpenApiKey();
        key.setId("k1");
        when(keyService.createManagedChatKey(any(), any(), any(), any(), any(), any())).thenReturn(key);
        service = new ShareLinkService(linkRepository, visitorRepository, agentRepository, userRepository,
                mock(AgentConversationRepository.class), mock(AgentConversationService.class), auth, keyService, settings,
                mock(AuditRecorder.class));
    }

    private static ShareLinkService.LinkRequest named(String name) {
        return new ShareLinkService.LinkRequest(name, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("默认：访问码模式、6 位访问码只返回一次、7 天有效、200 次对话、每分钟 10 条、标题用智能体名称")
    void createsWithSafeDefaults() {
        Map<String, Object> view = service.create("a1", named("给 A 客户演示"), dev);

        assertThat(view.get("accessMode")).isEqualTo(AgentShareLink.MODE_ACCESS_CODE);
        assertThat((String) view.get("accessCode")).matches("\\d{6}");
        assertThat(view.get("maxMessages")).isEqualTo(200);
        assertThat(view.get("visitorRpm")).isEqualTo(10);
        assertThat(view.get("title")).isEqualTo("客服助手");
        assertThat(view.get("state")).isEqualTo("ACTIVE");
        AgentShareLink link = saved.values().iterator().next();
        assertThat(link.getAccessCodeHash()).isNotEqualTo(view.get("accessCode"));
        assertThat(ShareLinkService.matchesAccessCode((String) view.get("accessCode"), link.getAccessCodeHash())).isTrue();
        assertThat(link.getExpiresAt()).isBetween(LocalDateTime.now().plusDays(7).minusMinutes(1), LocalDateTime.now().plusDays(7));
        assertThat(link.getCode()).hasSizeGreaterThanOrEqualTo(22);
        assertThat(link.getApiKeyId()).isEqualTo("k1");
        verify(keyService).createManagedChatKey(eq("u1"), eq("u1"), any(), eq("a1"), anyString(), eq(OpenApiKeyService.MANAGED_BY_SHARE_LINK));
        // 列表视图里不再有访问码明文
        assertThat(service.toView(link, agent, false, null)).doesNotContainKey("accessCode");
    }

    @Test
    @DisplayName("「公开」链接要超级管理员在平台设置里打开")
    void publicModeNeedsPlatformSetting() {
        ShareLinkService.LinkRequest req = new ShareLinkService.LinkRequest("公开试用", "PUBLIC", null, null, null,
                null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.create("a1", req, dev)).hasMessageContaining("未开放");

        when(settings.sharePublicEnabled()).thenReturn(true);
        assertThat(service.create("a1", req, dev).get("accessMode")).isEqualTo("PUBLIC");
    }

    @Test
    @DisplayName("没有管理权限、只读观察员都不能管理链接")
    void requiresManagePermission() {
        when(auth.canManageAgent(any(), eq(agent))).thenReturn(false);
        assertThatThrownBy(() -> service.create("a1", named("x"), dev)).isInstanceOf(IllegalStateException.class);
        CurrentActor viewer = new CurrentActor("v1", "viewer", UserRole.VIEWER);
        assertThatThrownBy(() -> service.list("a1", viewer)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("有效期、访问码、上限的校验")
    void validatesInput() {
        assertThatThrownBy(() -> service.create("a1", new ShareLinkService.LinkRequest("x", null, null, 400, null,
                null, null, null, null, null, null, null, null), dev)).hasMessageContaining("最长");
        assertThatThrownBy(() -> service.create("a1", new ShareLinkService.LinkRequest("x", null, "12", null, null,
                null, null, null, null, null, null, null, null), dev)).hasMessageContaining("4～32");
        assertThatThrownBy(() -> service.create("a1", new ShareLinkService.LinkRequest("x", null, null, null, null,
                -1, null, null, null, null, null, null, null), dev)).hasMessageContaining("负数");
        // 0 表示不限
        Map<String, Object> view = service.create("a1", new ShareLinkService.LinkRequest("x", null, "abcd1234", 1, null,
                0, 0L, 0, 30, null, null, List.of("怎么发布节目？", " "), false), dev);
        assertThat(view.get("maxMessages")).isNull();
        assertThat(view.get("accessCode")).isEqualTo("abcd1234");
        assertThat(view.get("exampleQuestions")).isEqualTo(List.of("怎么发布节目？"));
        assertThat(view.get("showRecordNotice")).isEqualTo(false);
    }

    @Test
    @DisplayName("重置：新的链接码和内部凭证，旧凭证作废，访客令牌全部失效")
    void resetRotatesCodeKeyAndTokens() {
        Map<String, Object> created = service.create("a1", named("x"), dev);
        String linkId = (String) created.get("id");
        String oldCode = (String) created.get("code");
        OpenApiKey newKey = new OpenApiKey();
        newKey.setId("k2");
        when(keyService.createManagedChatKey(any(), any(), any(), any(), any(), any())).thenReturn(newKey);

        Map<String, Object> view = service.reset("a1", linkId, dev);

        assertThat(view.get("code")).isNotEqualTo(oldCode);
        assertThat(saved.get(linkId).getApiKeyId()).isEqualTo("k2");
        verify(keyService).revokeManaged("k1");
        verify(visitorRepository).revokeTokens(linkId);
    }

    @Test
    @DisplayName("链接对访客的状态：停用、过期、用完、公开被关闭、智能体停用")
    void effectiveStates() {
        LocalDateTime now = LocalDateTime.now();
        AgentShareLink link = new AgentShareLink();
        link.setAccessMode(AgentShareLink.MODE_ACCESS_CODE);
        link.setExpiresAt(now.plusDays(1));
        assertThat(ShareLinkService.effectiveState(link, agent, false, now)).isEqualTo("ACTIVE");

        link.setStatus(AgentShareLink.STATUS_DISABLED);
        assertThat(ShareLinkService.effectiveState(link, agent, false, now)).isEqualTo("DISABLED");
        link.setStatus(AgentShareLink.STATUS_ACTIVE);

        link.setExpiresAt(now.minusSeconds(1));
        assertThat(ShareLinkService.effectiveState(link, agent, false, now)).isEqualTo("EXPIRED");
        link.setExpiresAt(now.plusDays(1));

        link.setMaxMessages(3);
        link.setMessageCount(3);
        assertThat(ShareLinkService.effectiveState(link, agent, false, now)).isEqualTo("EXHAUSTED");
        link.setMaxMessages(null);
        link.setMaxTokens(100L);
        link.setTokenCount(100);
        assertThat(ShareLinkService.effectiveState(link, agent, false, now)).isEqualTo("EXHAUSTED");
        link.setMaxTokens(null);

        link.setAccessMode(AgentShareLink.MODE_PUBLIC);
        assertThat(ShareLinkService.effectiveState(link, agent, false, now)).isEqualTo("PUBLIC_DISABLED");
        assertThat(ShareLinkService.effectiveState(link, agent, true, now)).isEqualTo("ACTIVE");

        agent.setStatus(AgentStatus.DISABLED);
        assertThat(ShareLinkService.effectiveState(link, agent, true, now)).isEqualTo("AGENT_NOT_RUNNING");
        assertThat(ShareLinkService.effectiveState(link, null, true, now)).isEqualTo("AGENT_NOT_RUNNING");
    }
}
