package com.example.agentplatform.config;

import com.example.agentplatform.repository.ClientCredentialRepository;
import com.example.agentplatform.repository.GuardrailPolicyRepository;
import com.example.agentplatform.repository.UserRepository;
import com.example.agentplatform.security.OpenApiAuthFilter;
import com.example.agentplatform.service.OpenApiKeyService;
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
            GuardrailPolicyRepository policyRepository,
            ClientCredentialRepository clientRepository,
            ObjectMapper objectMapper) {
        FilterRegistrationBean<OpenApiAuthFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new OpenApiAuthFilter(openApiKeyService, userRepository, policyRepository, clientRepository, objectMapper));
        bean.addUrlPatterns("/open/v1/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return bean;
    }
}
