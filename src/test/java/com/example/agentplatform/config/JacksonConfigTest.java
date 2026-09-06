package com.example.agentplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigTest {

    @Test
    void providesConfiguredObjectMapper() {
        JacksonConfig config = new JacksonConfig();
        ObjectMapper mapper = config.objectMapper();
        assertThat(mapper).isNotNull();
    }
}