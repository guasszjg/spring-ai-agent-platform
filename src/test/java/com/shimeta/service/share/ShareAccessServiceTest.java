package com.shimeta.service.share;

import com.shimeta.model.Agent;
import com.shimeta.model.AgentShareLink;
import com.shimeta.model.AgentShareVisitor;
import com.shimeta.model.AgentStatus;
import com.shimeta.model.AppUser;
import com.shimeta.model.OpenApiKey;
import com.shimeta.model.UserStatus;
import com.shimeta.repository.AgentRepository;
import com.shimeta.repository.AgentShareLinkRepository;
import com.shimeta.repository.AgentShareVisitorRepository;
import com.shimeta.repository.UserRepository;
import com.shimeta.service.AuditRecorder;
import com.shimeta.service.OpenApiKeyService;
import com.shimeta.service.PlatformSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShareAccessServiceTest {

    private final AgentShareLinkRepository linkRepository = mock(AgentShareLinkRepository.class);
    private final AgentShareVisitorRepository visitorRepository = mock(AgentShareVisitorRepository.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final OpenApiKeyService keyService = mock(OpenApiKeyService.class);
    private final PlatformSettingService settings = mock(PlatformSettingService.class);
    private final Map<String, AgentShareVisitor> visitors = new HashMap<>();
    private ShareAccessService service;
    private AgentShareLink link;
    private Agent agent;
    private AppUser owner;

    @BeforeEach
    void setUp() {
        agent = new Agent();
        agent.setId("a1");
        agent.setStatus(AgentStatus.RUNNING);
        link = new AgentShareLink();
        link.setId("L1");
        link.setAgentId("a1");
        link.setCode("code-1");
        link.setAccessMode(AgentShareLink.MODE_ACCESS_CODE);
        link.setAccessCodeHash(new BCryptPasswordEncoder().encode("123456"));
        link.setExpiresAt(LocalDateTime.now().plusDays(1));
        link.setApiKeyId("k1");
        when(linkRepository.findByCode("code-1")).thenAnswer(inv -> Optional.of(link));
        when(agentRepository.findById("a1")).thenReturn(Optional.of(agent));
        when(linkRepository.reserveVisitor("L1")).thenReturn(1);
        when(visitorRepository.save(any())).thenAnswer(inv -> {
            AgentShareVisitor v = inv.getArgument(0);
            visitors.put(v.getId(), v);
            return v;
        });
        when(visitorRepository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(visitors.get(inv.<String>getArgument(0))));
        when(visitorRepository.findByTokenHash(anyString())).thenAnswer(inv -> visitors.values().stream()
                .filter(v -> inv.getArgument(0).equals(v.getTokenHash())).findFirst());
        OpenApiKey key = new OpenApiKey();
        key.setId("k1");
        key.setOwnerId("u1");
        when(keyService.findUsableManaged("k1")).thenReturn(Optional.of(key));
        owner = new AppUser();
        owner.setId("u1");
        owner.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById("u1")).thenReturn(Optional.of(owner));
        service = new ShareAccessService(linkRepository, visitorRepository, agentRepository, userRepository, keyService,
                settings, mock(AuditRecorder.class));
    }

    private static String code(Throwable e) {
        return ((ShareAccessException) e).getCode();
    }

    @Test
    @DisplayName("访问码正确才发令牌；令牌只存哈希；同一访客再次进入沿用访客 ID、不占新名额")
    void issuesTokenForCorrectCode() {
        assertThatThrownBy(() -> service.startSession("code-1", null, "000000", "1.1.1.1"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ShareAccessException.ACCESS_CODE_INVALID));

        ShareAccessService.IssuedToken first = service.startSession("code-1", null, "123456", "1.1.1.1");
        AgentShareVisitor stored = visitors.get(first.visitorId());
        assertThat(stored.getTokenHash()).isEqualTo(OpenApiKeyService.sha256(first.token())).isNotEqualTo(first.token());
        assertThat(first.expiresAt()).isBefore(LocalDateTime.now().plusHours(2).plusMinutes(1));

        ShareAccessService.IssuedToken again = service.startSession("code-1", first.visitorId(), "123456", "1.1.1.1");
        assertThat(again.visitorId()).isEqualTo(first.visitorId());
        verify(linkRepository).reserveVisitor("L1");
    }

    @Test
    @DisplayName("连续输错 5 次锁定 15 分钟，锁定期间正确的访问码也不行")
    void locksAfterFiveFailures() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.startSession("code-1", null, "999999", "2.2.2.2"))
                    .satisfies(e -> assertThat(code(e)).isEqualTo(ShareAccessException.ACCESS_CODE_INVALID));
        }
        assertThatThrownBy(() -> service.startSession("code-1", null, "123456", "2.2.2.2"))
                .satisfies(e -> {
                    assertThat(code(e)).isEqualTo(ShareAccessException.TOO_MANY_ATTEMPTS);
                    assertThat(((ShareAccessException) e).getRetryAfterSeconds()).isGreaterThan(800);
                });
        // 其他访客不受影响
        assertThat(service.startSession("code-1", null, "123456", "3.3.3.3").token()).isNotBlank();
    }

    @Test
    @DisplayName("访客数已满时新访客不能进入")
    void rejectsWhenVisitorLimitReached() {
        when(linkRepository.reserveVisitor("L1")).thenReturn(0);
        assertThatThrownBy(() -> service.startSession("code-1", null, "123456", "1.1.1.1"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ShareAccessException.VISITOR_LIMIT));
        verify(visitorRepository, never()).save(any());
    }

    @Test
    @DisplayName("五种失效情况：过期、用完、停用、智能体停用、公开被关闭，统一返回链接失效")
    void invalidLinksAreRejectedUniformly() {
        String token = service.startSession("code-1", null, "123456", "1.1.1.1").token();
        assertThat(service.authenticate("code-1", token).agent()).isSameAs(agent);

        link.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        assertInvalid(token);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        link.setMaxMessages(1);
        link.setMessageCount(1);
        assertInvalid(token);
        link.setMaxMessages(null);

        link.setStatus(AgentShareLink.STATUS_DISABLED);
        assertInvalid(token);
        link.setStatus(AgentShareLink.STATUS_ACTIVE);

        agent.setStatus(AgentStatus.DISABLED);
        assertInvalid(token);
        agent.setStatus(AgentStatus.RUNNING);

        link.setAccessMode(AgentShareLink.MODE_PUBLIC);
        assertInvalid(token);
        when(settings.sharePublicEnabled()).thenReturn(true);
        assertThat(service.authenticate("code-1", token)).isNotNull();
    }

    private void assertInvalid(String token) {
        assertThatThrownBy(() -> service.authenticate("code-1", token))
                .satisfies(e -> {
                    assertThat(code(e)).isEqualTo(ShareAccessException.LINK_INVALID);
                    assertThat(e.getMessage()).isEqualTo("链接已失效，请联系提供方");
                });
    }

    @Test
    @DisplayName("令牌过期、属于别的链接、链接重置后旧地址：分别返回需要重新进入或链接失效")
    void tokenChecks() {
        String token = service.startSession("code-1", null, "123456", "1.1.1.1").token();
        assertThatThrownBy(() -> service.authenticate("code-1", null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ShareAccessException.SESSION_EXPIRED));
        assertThatThrownBy(() -> service.authenticate("code-1", "not-a-token"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ShareAccessException.SESSION_EXPIRED));

        visitors.values().forEach(v -> v.setTokenExpiresAt(LocalDateTime.now().minusSeconds(1)));
        assertThatThrownBy(() -> service.authenticate("code-1", token))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ShareAccessException.SESSION_EXPIRED));

        // 重置后旧链接码查不到链接
        when(linkRepository.findByCode("code-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.authenticate("code-1", token))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ShareAccessException.LINK_INVALID));
    }

    @Test
    @DisplayName("内部凭证被作废或所有者被停用：链接失效")
    void unusableKeyOrDisabledOwnerInvalidatesLink() {
        String token = service.startSession("code-1", null, "123456", "1.1.1.1").token();
        owner.setStatus(UserStatus.DISABLED);
        assertInvalid(token);
        owner.setStatus(UserStatus.ACTIVE);
        when(keyService.findUsableManaged("k1")).thenReturn(Optional.empty());
        assertInvalid(token);
    }
}
