package com.example.agentplatform.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DifyKnowledgeBaseProviderTest {

    @Test
    void normalizeDifyBaseUrl_addsSchemeAndV1() {
        assertThat(DifyKnowledgeBaseProvider.normalizeDifyBaseUrl("")).isEmpty();
        assertThat(DifyKnowledgeBaseProvider.normalizeDifyBaseUrl("  ")).isEmpty();
        assertThat(DifyKnowledgeBaseProvider.normalizeDifyBaseUrl("120.79.38.143"))
                .isEqualTo("http://120.79.38.143/v1");
        assertThat(DifyKnowledgeBaseProvider.normalizeDifyBaseUrl("http://120.79.38.143/"))
                .isEqualTo("http://120.79.38.143/v1");
        assertThat(DifyKnowledgeBaseProvider.normalizeDifyBaseUrl("http://120.79.38.143/v1/"))
                .isEqualTo("http://120.79.38.143/v1");
        assertThat(DifyKnowledgeBaseProvider.normalizeDifyBaseUrl("https://dify.example.com/v1"))
                .isEqualTo("https://dify.example.com/v1");
    }
}
