package com.shimeta.service;

import com.shimeta.model.OpenApiKey;
import com.shimeta.model.UserRole;
import com.shimeta.repository.AgentRepository;
import com.shimeta.repository.OpenApiKeyRepository;
import com.shimeta.repository.UserRepository;
import com.shimeta.security.CurrentActor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 平台管理的凭证（分享链接）不出现在凭证列表里，也不能手动操作 */
class OpenApiKeyManagedTest {

    private final OpenApiKeyRepository repo = mock(OpenApiKeyRepository.class);
    private final OpenApiKeyService service = new OpenApiKeyService(repo, mock(UserRepository.class),
            mock(AgentRepository.class), mock(AuditRecorder.class), mock(OwnerNameResolver.class));

    private static OpenApiKey key(String id, String managedBy) {
        OpenApiKey k = new OpenApiKey();
        k.setId(id);
        k.setOwnerId("u1");
        k.setManagedBy(managedBy);
        return k;
    }

    @Test
    @DisplayName("列表里没有分享链接的内部凭证；按 ID 也不能修改它")
    void managedKeysAreHidden() {
        CurrentActor admin = new CurrentActor("u1", "admin", UserRole.SUPER_ADMIN);
        when(repo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(key("k1", null), key("k2", "SHARE_LINK")));
        when(repo.findById("k2")).thenReturn(Optional.of(key("k2", "SHARE_LINK")));

        assertThat(service.listForActor(admin)).extracting(OpenApiKey::getId).containsExactly("k1");
        assertThatThrownBy(() -> service.requireOwned("k2", admin)).hasMessageContaining("平台自动管理");
    }

    @Test
    @DisplayName("内部凭证只有 chat 权限、只能调用一个智能体，明文不返回")
    void createsNarrowManagedKey() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        OpenApiKey k = service.createManagedChatKey("u1", "u1", 1, "a1", "分享链接「演示」", "SHARE_LINK");

        assertThat(k.getScopes()).containsExactly("chat");
        assertThat(k.getAgentScope()).containsExactly("a1");
        assertThat(k.getManagedBy()).isEqualTo("SHARE_LINK");
        assertThat(k.getKeyHash()).hasSize(64);
        assertThat(k.getKeyPrefix()).startsWith("managed-");
    }
}
