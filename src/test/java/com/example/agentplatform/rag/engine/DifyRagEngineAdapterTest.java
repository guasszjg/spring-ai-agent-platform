package com.example.agentplatform.rag.engine;

import com.example.agentplatform.rag.DifyKnowledgeBaseProvider;
import com.example.agentplatform.rag.RetrievedChunk;
import com.example.agentplatform.rag.dto.DifyDatasetDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DifyRagEngineAdapterTest {

    @Mock
    private DifyKnowledgeBaseProvider difyProvider;

    private DifyRagEngineAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new DifyRagEngineAdapter(difyProvider);
    }

    @Test
    void getEngineType_returnsDify() {
        assertThat(adapter.getEngineType()).isEqualTo(EngineType.DIFY);
    }

    @Test
    void getCapabilities_containsSupportedFormats() {
        EngineCapabilities caps = adapter.getCapabilities();
        assertThat(caps.supportedExtensions()).contains("PDF", "MD", "TXT", "DOCX");
        assertThat(caps.supportsRerank()).isTrue();
    }

    @Test
    void createOrBindIndex_delegatesToDifyProvider() {
        DifyDatasetDto dto = new DifyDatasetDto();
        dto.setId("dataset-abc-123");
        when(difyProvider.createDataset(eq("测试知识库"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(dto);

        IndexBuildSpec spec = IndexBuildSpec.builder()
                .knowledgeBaseId("kb-1")
                .knowledgeBaseName("测试知识库")
                .build();

        EngineIndexHandle handle = adapter.createOrBindIndex(spec);
        assertThat(handle.handleId()).isEqualTo("dataset-abc-123");
        assertThat(handle.engineType()).isEqualTo(EngineType.DIFY);
        assertThat(handle.status()).isEqualTo("READY");
    }

    @Test
    void retrieve_delegatesToDifyProviderWithParameters() {
        RetrievalRequest request = RetrievalRequest.builder()
                .query("如何配置网络？")
                .topK(5)
                .scoreThreshold(0.6)
                .build();

        when(difyProvider.retrieve("ds-100", request))
                .thenReturn(List.of(new RetrievedChunk("配置说明", "网络手册.pdf", 0.88)));

        List<RetrievedChunk> chunks = adapter.retrieve("ds-100", request);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("配置说明");
    }

    @Test
    void deleteIndex_delegatesToDifyProvider() {
        adapter.deleteIndex("dataset-to-delete");
        verify(difyProvider).deleteDataset("dataset-to-delete");
    }
}
