package com.example.agentplatform.security;

import com.example.agentplatform.security.quota.QuotaGuard;
import com.example.agentplatform.security.ratelimit.RateLimiter;
import com.example.agentplatform.service.AuditRecorder;
import com.example.agentplatform.service.UsageRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class OpenApiTrafficGuardFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final QuotaGuard quotaGuard;
    private final AuditRecorder auditRecorder;
    private final UsageRecorder usageRecorder;
    private final ObjectMapper objectMapper;

    public OpenApiTrafficGuardFilter(RateLimiter rateLimiter,
                                     QuotaGuard quotaGuard,
                                     AuditRecorder auditRecorder,
                                     UsageRecorder usageRecorder,
                                     ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.quotaGuard = quotaGuard;
        this.auditRecorder = auditRecorder;
        this.usageRecorder = usageRecorder;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || ctx.getOwner() == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestId = ctx.getRequestId();

        // 1. Key 维度 RPM 令牌桶限流
        int keyRpm = 120;
        if (ctx.getPolicy() != null && ctx.getPolicy().getDefaultRpm() != null && ctx.getPolicy().getDefaultRpm() > 0) {
            keyRpm = ctx.getPolicy().getDefaultRpm();
        }
        RateLimiter.Result keyResult = rateLimiter.tryConsume("key:" + ctx.getKey().getId(), 1, keyRpm);
        response.setHeader("X-RateLimit-Limit", String.valueOf(keyResult.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(keyResult.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(keyResult.resetSeconds()));

        if (!keyResult.allowed()) {
            response.setHeader("Retry-After", String.valueOf(Math.max(1, keyResult.resetSeconds())));
            auditRecorder.record("ratelimit.key", "API_KEY", ctx.getKey().getId(), "DENIED", "429 rate_limited", "MEDIUM", "超限 RPM: " + keyRpm);
            usageRecorder.record("ratelimit.key", null, null, 429, "rate_limited", 0, 0, 0, null);
            writeError(response, 429, "rate_limited", "请求过于频繁，超出凭证限流阈值 (" + keyRpm + " RPM)", requestId);
            return;
        }

        // 2. 终端 Client 维度 RPM 限流 (如果终端单独配置了限流阈值)
        if (ctx.getClient() != null && ctx.getClient().getRateLimitRpm() != null && ctx.getClient().getRateLimitRpm() > 0) {
            int clientRpm = ctx.getClient().getRateLimitRpm();
            RateLimiter.Result clientResult = rateLimiter.tryConsume("client:" + ctx.getClient().getId(), 1, clientRpm);
            if (!clientResult.allowed()) {
                response.setHeader("Retry-After", String.valueOf(Math.max(1, clientResult.resetSeconds())));
                auditRecorder.record("ratelimit.client", "CLIENT", ctx.getClient().getId(), "DENIED", "429 rate_limited", "MEDIUM", "超限 RPM: " + clientRpm);
                usageRecorder.record("ratelimit.client", null, null, 429, "rate_limited", 0, 0, 0, null);
                writeError(response, 429, "rate_limited", "请求过于频繁，超出终端限流阈值 (" + clientRpm + " RPM)", requestId);
                return;
            }
        }

        // 3. 每日 Token 成本配额熔断检查
        QuotaGuard.Result quotaResult = quotaGuard.checkQuota(ctx);
        if (!quotaResult.allowed()) {
            auditRecorder.record("quota.exceeded", "QUOTA", ctx.getOwner().getId(), "DENIED", "429 " + quotaResult.code(), "HIGH", quotaResult.message());
            usageRecorder.record("quota.exceeded", null, null, 429, quotaResult.code(), 0, 0, 0, null);
            writeError(response, 429, quotaResult.code(), quotaResult.message(), requestId);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int status, String code, String message, String requestId) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("request_id", requestId);
        body.put("data", data);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
