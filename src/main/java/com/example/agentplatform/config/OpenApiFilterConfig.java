package com.example.agentplatform.config;

import com.example.agentplatform.repository.ClientCredentialRepository;
import com.example.agentplatform.repository.UserRepository;
import com.example.agentplatform.security.OpenApiAuthFilter;
import com.example.agentplatform.security.OpenApiIdempotencyFilter;
import com.example.agentplatform.security.OpenApiTrafficGuardFilter;
import com.example.agentplatform.security.quota.QuotaGuard;
import com.example.agentplatform.security.ratelimit.RateLimiter;
import com.example.agentplatform.service.AuditRecorder;
import com.example.agentplatform.service.GuardrailPolicyService;
import com.example.agentplatform.service.OpenApiKeyService;
import com.example.agentplatform.service.UsageRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class OpenApiFilterConfig {

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
        bean.addUrlPatterns("/open/v1/*");
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
        bean.addUrlPatterns("/open/v1/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 25);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<OpenApiIdempotencyFilter> openApiIdempotencyFilter() {
        FilterRegistrationBean<OpenApiIdempotencyFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new OpenApiIdempotencyFilter());
        bean.addUrlPatterns("/open/v1/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        return bean;
    }
}
