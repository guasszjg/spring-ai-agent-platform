package com.shimeta.security.quota;

import com.shimeta.model.Agent;
import com.shimeta.model.AppUser;
import com.shimeta.model.OpenApiKey;
import com.shimeta.model.UserRole;
import com.shimeta.repository.AgentRepository;
import com.shimeta.repository.OpenApiKeyRepository;
import com.shimeta.repository.UserRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.AuditRecorder;
import com.shimeta.service.OpenApiKeyService;
import com.shimeta.service.OwnerNameResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 调试页「访问 API」：列出能调用某个智能体的凭证，「重新生成」改为轮换；智能体表里的明文 Key 不再能鉴权 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentCredentialsTest {

    @Mock OpenApiKeyRepository keyRepository;
    @Mock UserRepository userRepository;
    @Mock AgentRepository agentRepository;
    @Mock AuditRecorder auditRecorder;
    @Mock OwnerNameResolver ownerNameResolver;

    private OpenApiKeyService service;
    private final CurrentActor dev = new CurrentActor("u-dev", "dev", UserRole.DEVELOPER);
    private final Agent agent = new Agent();

    @BeforeEach
    void setUp() {
        service = new OpenApiKeyService(keyRepository, userRepository, agentRepository, auditRecorder, ownerNameResolver);
        agent.setId("agent-1");
        agent.setName("广告机");
        agent.setOwnerId("u-dev");
        when(keyRepository.save(any(OpenApiKey.class))).thenAnswer(inv -> {
            OpenApiKey k = inv.getArgument(0);
            if (k.getId() == null) k.setId("oak-new");
            return k;
        });
        AppUser owner = new AppUser();
        owner.setId("u-dev");
        owner.setRole(UserRole.DEVELOPER.name());
        when(userRepository.findById("u-dev")).thenReturn(Optional.of(owner));
    }

    private static OpenApiKey key(String id, String status, List<String> scopes, List<String> agentScope) {
        OpenApiKey k = new OpenApiKey();
        k.setId(id);
        k.setName(id);
        k.setOwnerId("u-dev");
        k.setCreatedBy("u-dev");
        k.setKeyPrefix("sk-live-" + id);
        k.setStatus(status);
        k.setScopes(scopes);
        k.setAgentScope(agentScope);
        return k;
    }

    @Test
    @DisplayName("只列出能调用该智能体的凭证：专属的与不限智能体的，排除别的智能体专属、无对话权限与已吊销")
    void listsCredentialsThatCanCallAgent() {
        OpenApiKey dedicated = key("dedicated", "ACTIVE", List.of("chat"), List.of("agent-1"));
        OpenApiKey all = key("all", "ACTIVE", List.of("chat"), List.of());
        OpenApiKey rotating = key("rotating", "ROTATING", List.of("chat"), List.of("agent-1"));
        rotating.setGraceExpiresAt(LocalDateTime.now().plusHours(3));
        OpenApiKey expired = key("expired", "ACTIVE", List.of("chat"), List.of("agent-1"));
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        OpenApiKey other = key("other", "ACTIVE", List.of("chat"), List.of("agent-2"));
        OpenApiKey noChat = key("nochat", "ACTIVE", List.of("agents:read"), List.of());
        OpenApiKey revoked = key("revoked", "REVOKED", List.of("chat"), List.of("agent-1"));
        when(keyRepository.findByOwnerIdOrderByCreatedAtDesc("u-dev"))
                .thenReturn(List.of(dedicated, all, rotating, expired, other, noChat, revoked));

        List<Map<String, Object>> list = service.credentialsForAgent(agent, dev);

        assertThat(list).extracting(m -> m.get("id")).containsExactly("dedicated", "all", "rotating", "expired");
        assertThat(list).extracting(m -> m.get("binding")).containsExactly("DEDICATED", "ALL_AGENTS", "DEDICATED", "DEDICATED");
        assertThat(list).extracting(m -> m.get("usable")).containsExactly(true, true, true, false);
    }

    @Test
    @DisplayName("重新生成：已有专属启用凭证时轮换它，旧 Key 进入宽限期，不再多出一条并存的凭证")
    void regenerateRotatesDedicatedKey() {
        OpenApiKey dedicated = key("dedicated", "ACTIVE", List.of("chat"), List.of("agent-1"));
        when(keyRepository.findByOwnerIdOrderByCreatedAtDesc("u-dev"))
                .thenReturn(List.of(key("all", "ACTIVE", List.of("chat"), List.of()), dedicated));
        when(keyRepository.findById("dedicated")).thenReturn(Optional.of(dedicated));

        Map<String, Object> result = service.rotateOrIssueForAgent(agent, dev, 24);

        assertThat(result.get("mode")).isEqualTo("ROTATED");
        assertThat((String) result.get("plaintext")).startsWith("sk-live-");
        assertThat(result.get("key")).isNotNull();
        assertThat(dedicated.getStatus()).isEqualTo("ROTATING");
        assertThat(dedicated.getGraceExpiresAt()).isAfter(LocalDateTime.now().plusHours(23));
        assertThat(agent.getApiKey()).isNull();
    }

    @Test
    @DisplayName("重新生成：没有专属凭证时签发一条只限该智能体的新凭证，且不把明文写回智能体")
    void regenerateIssuesWhenNoDedicatedKey() {
        when(keyRepository.findByOwnerIdOrderByCreatedAtDesc("u-dev"))
                .thenReturn(List.of(key("all", "ACTIVE", List.of("chat"), List.of())));

        Map<String, Object> result = service.rotateOrIssueForAgent(agent, dev, 24);

        assertThat(result.get("mode")).isEqualTo("ISSUED");
        assertThat((String) result.get("plaintext")).startsWith("sk-live-");
        assertThat(agent.getApiKey()).isNull();
        verify(agentRepository, never()).save(any());
    }

    @Test
    @DisplayName("鉴权只认开放凭证的哈希，智能体表里的旧明文 Key 不再能调用")
    void legacyAgentPlaintextNoLongerAuthenticates() {
        when(keyRepository.findByKeyHash(anyString())).thenReturn(Optional.empty());

        assertThat(service.resolvePlaintext("sk-agent-legacy-plaintext")).isEmpty();
    }
}
