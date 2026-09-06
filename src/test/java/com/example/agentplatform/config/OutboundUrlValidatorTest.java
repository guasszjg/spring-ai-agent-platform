package com.example.agentplatform.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundUrlValidatorTest {

    private final OutboundUrlValidator validator = new OutboundUrlValidator(false);

    @Test
    void rejectsLoopbackAndPrivateAddresses() {
        assertThatThrownBy(() -> validator.validateProviderBaseUrl("http://127.0.0.1:11434/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("本机或内网");
        assertThatThrownBy(() -> validator.validateProviderBaseUrl("http://192.168.1.10/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("本机或内网");
    }

    @Test
    void rejectsUnsupportedSchemesAndEmbeddedCredentials() {
        assertThatThrownBy(() -> validator.validateProviderBaseUrl("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateProviderBaseUrl("https://user:pass@example.com/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户凭据");
    }

    @Test
    void allowsPrivateAddressesWhenExplicitlyEnabledForDevelopment() {
        OutboundUrlValidator developmentValidator = new OutboundUrlValidator(true);
        developmentValidator.validateProviderBaseUrl("http://127.0.0.1:11434/v1");
        developmentValidator.validateProviderBaseUrl("http://192.168.1.10:8000/v1");
    }
}
