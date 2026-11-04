package com.shimeta.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicUrlResolverTest {

    @Test
    void usesConfiguredBaseUrlWhenPresent() {
        PublicUrlResolver resolver = new PublicUrlResolver("https://ai.example.com/");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("10.0.0.5");
        request.setServerPort(8080);

        assertThat(resolver.baseUrl(request)).isEqualTo("https://ai.example.com");
    }

    @Test
    void fallsBackToRequestHost() {
        PublicUrlResolver resolver = new PublicUrlResolver("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("10.0.0.5");
        request.setServerPort(8080);

        assertThat(resolver.baseUrl(request)).isEqualTo("http://10.0.0.5:8080");
    }

    @Test
    void absoluteKeepsFullUrlsAndPrefixesRelativeOnes() {
        assertThat(PublicUrlResolver.absolute(
                List.of("/uploads/knowledge/a.png", "uploads/b.png", "https://cdn.example.com/c.png", " "),
                "http://10.0.0.5:8080"))
                .containsExactly("http://10.0.0.5:8080/uploads/knowledge/a.png",
                        "http://10.0.0.5:8080/uploads/b.png",
                        "https://cdn.example.com/c.png");
        assertThat(PublicUrlResolver.absolute(null, "http://x")).isEmpty();
    }
}
