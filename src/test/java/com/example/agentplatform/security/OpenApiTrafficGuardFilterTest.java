package com.example.agentplatform.security;

import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.GuardrailPolicy;
import com.example.agentplatform.model.OpenApiKey;
import com.example.agentplatform.security.quota.QuotaGuard;
import com.example.agentplatform.security.ratelimit.RateLimiter;
import com.example.agentplatform.service.AuditRecorder;
import com.example.agentplatform.service.UsageRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenApiTrafficGuardFilterTest {

    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private QuotaGuard quotaGuard;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private UsageRecorder usageRecorder;
    @Mock
    private FilterChain filterChain;

    private OpenApiTrafficGuardFilter filter;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private AppUser owner;
    private OpenApiKey key;

    @BeforeEach
    void setUp() {
        filter = new OpenApiTrafficGuardFilter(rateLimiter, quotaGuard, auditRecorder, usageRecorder, objectMapper);
        owner = new AppUser();
        owner.setId("usr-guard-1");
        key = new OpenApiKey();
        key.setId("oak-guard-1");

        OpenApiContext ctx = new OpenApiContext("req-test-guard", key, owner, "127.0.0.1");
        GuardrailPolicy policy = new GuardrailPolicy();
        policy.setDefaultRpm(60);
        ctx.setPolicy(policy);
        OpenApiContext.set(ctx);
    }

    @AfterEach
    void tearDown() {
        OpenApiContext.clear();
    }

    @Test
    @DisplayName("限流触发时拦截返回 429 rate_limited 并包含 X-RateLimit-* 头")
    void rateLimitTriggered() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/open/v1/chat-messages");
        MockHttpServletResponse res = new MockHttpServletResponse();

        when(rateLimiter.tryConsume(eq("key:oak-guard-1"), eq(1), eq(60)))
                .thenReturn(new RateLimiter.Result(false, 60, 0, 45));

        filter.doFilter(req, res, filterChain);

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("X-RateLimit-Limit")).isEqualTo("60");
        assertThat(res.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(res.getHeader("X-RateLimit-Reset")).isEqualTo("45");
        assertThat(res.getHeader("Retry-After")).isEqualTo("45");
        assertThat(res.getContentAsString()).contains("rate_limited");

        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("配额超额时拦截返回 429 quota_exceeded")
    void quotaLimitTriggered() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/open/v1/chat-messages");
        MockHttpServletResponse res = new MockHttpServletResponse();

        when(rateLimiter.tryConsume(eq("key:oak-guard-1"), eq(1), anyInt()))
                .thenReturn(new RateLimiter.Result(true, 60, 59, 0));
        when(quotaGuard.checkQuota(any()))
                .thenReturn(QuotaGuard.Result.deny("quota_exceeded", "已超出今日配额"));

        filter.doFilter(req, res, filterChain);

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getContentAsString()).contains("quota_exceeded");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("正常请求设置限流头并放行到下游")
    void normalRequest_passesThrough() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/open/v1/agents");
        MockHttpServletResponse res = new MockHttpServletResponse();

        when(rateLimiter.tryConsume(eq("key:oak-guard-1"), eq(1), anyInt()))
                .thenReturn(new RateLimiter.Result(true, 60, 55, 12));
        when(quotaGuard.checkQuota(any()))
                .thenReturn(QuotaGuard.Result.allow());

        filter.doFilter(req, res, filterChain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("X-RateLimit-Limit")).isEqualTo("60");
        assertThat(res.getHeader("X-RateLimit-Remaining")).isEqualTo("55");
        verify(filterChain).doFilter(req, res);
    }
}
