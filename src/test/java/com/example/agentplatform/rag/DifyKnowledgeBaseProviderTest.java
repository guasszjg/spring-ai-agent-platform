package com.example.agentplatform.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DifyKnowledgeBaseProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Test
    void parseRetrieveResponse_readsSegmentContentAndDocumentName() throws Exception {
        String json = """
                {
                  "records": [
                    {
                      "score": 0.88,
                      "segment": {
                        "content": "设备重启步骤",
                        "document": { "name": "手册.pdf" }
                      }
                    },
                    {
                      "segment": { "content": "   " }
                    }
                  ]
                }
                """;
        List<RetrievedChunk> chunks = DifyKnowledgeBaseProvider.parseRetrieveResponse(objectMapper.readTree(json));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("设备重启步骤");
        assertThat(chunks.get(0).sourceName()).isEqualTo("手册.pdf");
        assertThat(chunks.get(0).score()).isEqualTo(0.88);
    }
}
