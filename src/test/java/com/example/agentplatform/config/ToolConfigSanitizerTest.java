package com.example.agentplatform.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolConfigSanitizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolConfigSanitizer sanitizer = new ToolConfigSanitizer();

    @Test
    void removesNestedSecretsAndKeepsNonSensitiveSettings() throws Exception {
        String input = "[{\"name\":\"联网检索\",\"config\":{\"apiKey\":\"secret\",\"count\":5}},"
                + "{\"token\":\"secret-2\",\"enabled\":true}]";

        JsonNode result = objectMapper.readTree(sanitizer.sanitize(input));

        assertThat(result.at("/0/config/apiKey").isMissingNode()).isTrue();
        assertThat(result.at("/0/config/count").asInt()).isEqualTo(5);
        assertThat(result.at("/1/token").isMissingNode()).isTrue();
        assertThat(result.at("/1/enabled").asBoolean()).isTrue();
    }

    @Test
    void discardsMalformedConfiguration() {
        assertThat(sanitizer.sanitize("{broken-json")).isNull();
    }
}
