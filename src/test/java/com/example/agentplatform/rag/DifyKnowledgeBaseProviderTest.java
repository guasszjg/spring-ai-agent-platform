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

    @Test
    void parseRetrieveResponse_readsFullEvidenceMetadata() throws Exception {
        String json = """
                {
                  "records": [
                    {
                      "score": 0.95,
                      "segment": {
                        "id": "seg-1001",
                        "document_id": "doc-5001",
                        "position": 3,
                        "tokens": 128,
                        "status": "completed",
                        "content": "集群高可用部署配置详解",
                        "document": {
                          "id": "doc-5001",
                          "name": "部署手册.pdf"
                        }
                      }
                    }
                  ]
                }
                """;
        List<RetrievedChunk> chunks = DifyKnowledgeBaseProvider.parseRetrieveResponse(objectMapper.readTree(json));
        assertThat(chunks).hasSize(1);
        RetrievedChunk chunk = chunks.get(0);
        assertThat(chunk.chunkId()).isEqualTo("seg-1001");
        assertThat(chunk.documentId()).isEqualTo("doc-5001");
        assertThat(chunk.sourceName()).isEqualTo("部署手册.pdf");
        assertThat(chunk.content()).isEqualTo("集群高可用部署配置详解");
        assertThat(chunk.score()).isEqualTo(0.95);
        assertThat(chunk.vectorScore()).isEqualTo(0.95);
        assertThat(chunk.tokenCount()).isEqualTo(128);
        assertThat(chunk.metadata()).containsEntry("position", 3);
        assertThat(chunk.metadata()).containsEntry("status", "completed");
    }
}
