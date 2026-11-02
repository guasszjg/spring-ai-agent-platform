package com.shimeta.config;

import com.shimeta.repository.ClientCredentialRepository;
import com.shimeta.repository.UserRepository;
import com.shimeta.security.OpenApiAuthFilter;
import com.shimeta.security.OpenApiIdempotencyFilter;
import com.shimeta.security.OpenApiTrafficGuardFilter;
import com.shimeta.security.quota.QuotaGuard;
import com.shimeta.security.ratelimit.RateLimiter;
import com.shimeta.service.AuditRecorder;
import com.shimeta.service.GuardrailPolicyService;
import com.shimeta.service.OpenApiKeyService;
import com.shimeta.service.UsageRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class OpenApiFilterConfig {

    /**
     * 开放调用的所有入口：/open/v1 与早期兼容地址 /api/v1。
     * 两者必须走同一套鉴权（凭证状态、过期、IP 白名单、终端管控）、限流配额与幂等，否则旧地址会成为绕过安全检查的后门
     */
    static final String[] OPEN_API_URL_PATTERNS = {"/open/v1/*", "/api/v1/*"};

    @Bean
    public FilterRegistrationBean<OpenApiAuthFilter> openApiAuthFilter(
            OpenApiKeyService openApiKeyService,
            UserRepository userRepository,
            GuardrailPolicyService policyService,
            ClientCredentialRepository clientRepository,
            AuditRecorder auditRecorder,
            ObjectMapper objectMapper) {
        FilterRegistrationBean<OpenApiAuthFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new OpenApiAuthFilter(openApiKeyService, userRepository, policyService, clientRepository, auditRecorder, objectMapper));
        bean.addUrlPatterns(OPEN_API_URL_PATTERNS);
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<OpenApiTrafficGuardFilter> openApiTrafficGuardFilter(
            RateLimiter rateLimiter,
            QuotaGuard quotaGuard,
            AuditRecorder auditRecorder,
            UsageRecorder usageRecorder,
            ObjectMapper objectMapper) {
        FilterRegistrationBean<OpenApiTrafficGuardFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new OpenApiTrafficGuardFilter(rateLimiter, quotaGuard, auditRecorder, usageRecorder, objectMapper));
        bean.addUrlPatterns(OPEN_API_URL_PATTERNS);
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 25);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<OpenApiIdempotencyFilter> openApiIdempotencyFilter() {
        FilterRegistrationBean<OpenApiIdempotencyFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new OpenApiIdempotencyFilter());
        bean.addUrlPatterns(OPEN_API_URL_PATTERNS);
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        return bean;
    }
}
