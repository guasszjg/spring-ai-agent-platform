package com.example.agentplatform.security.quota;

import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.CreateOpenApiKeyRequest;
import com.example.agentplatform.model.OpenApiKey;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.repository.OpenApiKeyRepository;
import com.example.agentplatform.repository.UserRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.AuditRecorder;
import com.example.agentplatform.service.OpenApiKeyService;
import com.example.agentplatform.service.OwnerNameResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KeyRotationTest {

    @Mock
    private OpenApiKeyRepository keyRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private OwnerNameResolver ownerNameResolver;

    private OpenApiKeyService keyService;

    @BeforeEach
    void setUp() {
        keyService = new OpenApiKeyService(keyRepository, userRepository, agentRepository, auditRecorder, ownerNameResolver);
    }

    @Test
    @DisplayName("Key平滑轮换: 生成新Key，旧Key进入24h宽限期")
    void testKeyGracefulRotation() {
        String ownerId = "dev-user-01";
        CurrentActor actor = new CurrentActor(ownerId, "developer", com.example.agentplatform.model.UserRole.DEVELOPER);

        OpenApiKey oldKey = new OpenApiKey();
        oldKey.setId("oak-old-12345678");
        oldKey.setName("生产网关凭证");
        oldKey.setOwnerId(ownerId);
        oldKey.setCreatedBy(ownerId);
        oldKey.setKeyPrefix("sk-live-old12345");
        oldKey.setKeyHash(OpenApiKeyService.sha256("sk-live-old1234567890"));
        oldKey.setScopes(List.of("chat"));
        oldKey.setStatus("ACTIVE");

        when(keyRepository.findById("oak-old-12345678")).thenReturn(Optional.of(oldKey));
        when(keyRepository.save(any(OpenApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = keyService.rotate("oak-old-12345678", 24, actor);

        assertThat(result).containsKey("newKey");
        assertThat(result).containsKey("newPlaintext");
        assertThat(result).containsKey("oldKey");
        assertThat(result.get("graceHours")).isEqualTo(24);

        // 验证旧 Key 状态变为 ROTATING 且具有宽限过期时间
        assertThat(oldKey.getStatus()).isEqualTo("ROTATING");
        assertThat(oldKey.getGraceExpiresAt()).isNotNull();
        assertThat(oldKey.getGraceExpiresAt()).isAfter(LocalDateTime.now().plusHours(23));
        assertThat(oldKey.getRotatedToKeyId()).isNotNull();

        // 验证调用了审计记录
        verify(auditRecorder).record(eq("key.rotate"), eq("API_KEY"), eq("oak-old-12345678"), eq("SUCCESS"), isNull(), eq("MEDIUM"), anyString());
    }

    @Test
    @DisplayName("Key宽限期过期后: resolvePlaintext 自动转为 REVOKED")
    void testRotatingKeyExpiredAutomaticallyRevoked() {
        String plaintext = "sk-live-old1234567890";
        String hash = OpenApiKeyService.sha256(plaintext);

        OpenApiKey rotatingKey = new OpenApiKey();
        rotatingKey.setId("oak-old-12345678");
        rotatingKey.setKeyHash(hash);
        rotatingKey.setStatus("ROTATING");
        // 宽限期已经在 1 小时前结束
        rotatingKey.setGraceExpiresAt(LocalDateTime.now().minusHours(1));

        when(keyRepository.findByKeyHash(hash)).thenReturn(Optional.of(rotatingKey));
        when(keyRepository.save(any(OpenApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<OpenApiKeyService.ResolvedKey> resolved = keyService.resolvePlaintext(plaintext);

        assertThat(resolved).isPresent();
        // 验证状态已被更新为 REVOKED
        assertThat(resolved.get().key().getStatus()).isEqualTo("REVOKED");
        verify(keyRepository).save(rotatingKey);
    }
}
