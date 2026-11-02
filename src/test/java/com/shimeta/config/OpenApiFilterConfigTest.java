package com.shimeta.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.model.OpenApiKey;
import com.shimeta.repository.ClientCredentialRepository;
import com.shimeta.repository.UserRepository;
import com.shimeta.security.OpenApiAuthFilter;
import com.shimeta.security.quota.QuotaGuard;
import com.shimeta.security.ratelimit.RateLimiter;
import com.shimeta.service.AuditRecorder;
import com.shimeta.service.GuardrailPolicyService;
import com.shimeta.service.OpenApiKeyService;
import com.shimeta.service.OpenApiKeyService.ResolvedKey;
import com.shimeta.service.UsageRecorder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 兼容地址 /api/v1 与 /open/v1 走同一套安全检查，旧地址不能绕过凭证状态、IP 白名单、终端管控与限流 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenApiFilterConfigTest {

    @Mock OpenApiKeyService keyService;
    @Mock UserRepository userRepository;
    @Mock GuardrailPolicyService policyService;
    @Mock ClientCredentialRepository clientRepository;
    @Mock AuditRecorder auditRecorder;
    @Mock UsageRecorder usageRecorder;
    @Mock RateLimiter rateLimiter;
    @Mock QuotaGuard quotaGuard;
    @Mock FilterChain chain;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenApiFilterConfig config = new OpenApiFilterConfig();

    @Test
    @DisplayName("鉴权、限流配额、幂等三个过滤器都同时覆盖 /open/v1 与 /api/v1")
    void allFiltersCoverBothEntrances() {
        List<FilterRegistrationBean<?>> beans = List.of(
                config.openApiAuthFilter(keyService, userRepository, policyService, clientRepository, auditRecorder, objectMapper),
                config.openApiTrafficGuardFilter(rateLimiter, quotaGuard, auditRecorder, usageRecorder, objectMapper),
                config.openApiIdempotencyFilter());

        for (FilterRegistrationBean<?> bean : beans) {
            assertThat(bean.getUrlPatterns()).containsExactlyInAnyOrder("/open/v1/*", "/api/v1/*");
        }
    }

    private OpenApiKey key(String status) {
        OpenApiKey key = new OpenApiKey();
        key.setId("oak-1");
        key.setOwnerId("u-dev");
        key.setStatus(status);
        key.setScopes(List.of("chat"));
        return key;
    }

    private MockHttpServletResponse callApiV1(String plaintext) throws Exception {
        OpenApiAuthFilter filter = config.openApiAuthFilter(keyService, userRepository, policyService,
                clientRepository, auditRecorder, objectMapper).getFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat-messages");
        if (plaintext != null) {
            request.addHeader("Authorization", "Bearer " + plaintext);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVOKED", "DISABLED"})
    @DisplayName("已吊销 / 已停用的凭证在 /api/v1 上被拒绝，请求到不了对话服务")
    void revokedOrDisabledKeyIsRejectedOnApiV1(String status) throws Exception {
        when(keyService.resolvePlaintext("sk-live-old")).thenReturn(Optional.of(new ResolvedKey(key(status))));

        MockHttpServletResponse response = callApiV1("sk-live-old");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("key_revoked");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("已过期、轮换超过宽限期、没带 Key 的请求在 /api/v1 上都被拒绝")
    void expiredMissingAndPastGraceKeysAreRejectedOnApiV1() throws Exception {
        OpenApiKey expired = key("ACTIVE");
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(keyService.resolvePlaintext("sk-live-expired")).thenReturn(Optional.of(new ResolvedKey(expired)));
        OpenApiKey pastGrace = key("ROTATING");
        pastGrace.setGraceExpiresAt(LocalDateTime.now().minusHours(1));
        when(keyService.resolvePlaintext("sk-live-grace")).thenReturn(Optional.of(new ResolvedKey(pastGrace)));

        assertThat(callApiV1("sk-live-expired").getContentAsString()).contains("key_expired");
        assertThat(callApiV1("sk-live-grace").getContentAsString()).contains("key_expired");
        assertThat(callApiV1(null).getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }
}
