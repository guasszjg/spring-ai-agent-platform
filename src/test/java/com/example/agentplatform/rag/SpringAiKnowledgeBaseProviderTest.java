package com.example.agentplatform.rag;

import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.KnowledgeDocumentChunk;
import com.example.agentplatform.rag.dto.DifyDatasetDto;
import com.example.agentplatform.rag.dto.DifyDocumentDto;
import com.example.agentplatform.rag.engine.RetrievalRequest;
import com.example.agentplatform.rag.pipeline.DocumentChunker;
import com.example.agentplatform.rag.pipeline.KeywordRetriever;
import com.example.agentplatform.rag.pipeline.LocalEmbeddingService;
import com.example.agentplatform.repository.KnowledgeBaseRepository;
import com.example.agentplatform.repository.KnowledgeDocumentChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringAiKnowledgeBaseProviderTest {

    @Mock
    private KnowledgeDocumentChunkRepository chunkRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    private DocumentChunker documentChunker;
    private LocalEmbeddingService embeddingService;
    private KeywordRetriever keywordRetriever;
    private SpringAiKnowledgeBaseProvider provider;

    @BeforeEach
    void setUp() {
        documentChunker = new DocumentChunker();
        embeddingService = new LocalEmbeddingService(null);
        keywordRetriever = new KeywordRetriever();
        provider = new SpringAiKnowledgeBaseProvider(
                chunkRepository,
                knowledgeBaseRepository,
                documentChunker,
                embeddingService,
                keywordRetriever
        );
    }

    @Test
    void testProviderTypeAndBasics() {
        assertThat(provider.getProviderType()).isEqualTo("SPRING_AI");
        assertThat(provider.getBaseUrl()).contains("spring-ai-rag-engine");

        DifyDatasetDto dataset = provider.createDataset(
                "测试自研库", "自研描述", "high_quality", "only_me",
                "text-embedding-v3", "spring_ai", "hybrid_search",
                3, true, "weighted_score", "qwen3-rerank", "spring_ai",
                0.7, 0.3
        );
        assertThat(dataset).isNotNull();
        assertThat(dataset.getId()).startsWith("spring_ai_kb_");
        assertThat(dataset.getName()).isEqualTo("测试自研库");
    }

    @Test
    void testUploadDocumentAndChunking() {
        String text = "Spring AI 是一个用于构建 AI 应用程序的应用框架。\n\n它提供了统一的 Client 抽象、Prompt 管理以及对向量存储和 RAG 管道的开箱即用支持。\n\n本平台在此基础上自研了高内聚的私有化检索与切片索引管线。";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "spring-ai-guide.txt",
                "text/plain",
                text.getBytes(StandardCharsets.UTF_8)
        );

        when(knowledgeBaseRepository.findByExternalDatasetId(anyString())).thenReturn(Optional.empty());
        when(knowledgeBaseRepository.findById(anyString())).thenReturn(Optional.empty());
        when(chunkRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        DifyDocumentDto doc = provider.uploadDocument("kb_test_123", file);

        assertThat(doc).isNotNull();
        assertThat(doc.getName()).isEqualTo("spring-ai-guide.txt");
        assertThat(doc.getIndexingStatus()).isEqualTo("completed");
        assertThat(doc.getWordCount()).isGreaterThan(0L);
        assertThat(doc.getTokens()).isGreaterThan(0L);

        verify(chunkRepository).saveAll(anyList());
    }

    @Test
    void testRetrieveHybrid() {
        KnowledgeDocumentChunk chunk1 = new KnowledgeDocumentChunk();
        chunk1.setId("c1");
        chunk1.setKnowledgeBaseId("kb_test_123");
        chunk1.setChunkIndex(0);
        chunk1.setContent("退款申请提交后，财务部门将在 1-3 个工作日内原路退回款项。");
        chunk1.setTokenCount(35L);
        chunk1.setEnabled(true);
        chunk1.setEmbedding(embeddingService.serializeVector(embeddingService.embed(chunk1.getContent(), 1024)));

        KnowledgeDocumentChunk chunk2 = new KnowledgeDocumentChunk();
        chunk2.setId("c2");
        chunk2.setKnowledgeBaseId("kb_test_123");
        chunk2.setChunkIndex(1);
        chunk2.setContent("智能体支持自定义提示词与多模态文件解析。");
        chunk2.setTokenCount(25L);
        chunk2.setEnabled(true);
        chunk2.setEmbedding(embeddingService.serializeVector(embeddingService.embed(chunk2.getContent(), 1024)));

        when(knowledgeBaseRepository.findByExternalDatasetId(anyString())).thenReturn(Optional.empty());
        when(knowledgeBaseRepository.findById(anyString())).thenReturn(Optional.empty());
        when(chunkRepository.findByKnowledgeBaseIdAndEnabledTrueOrderByChunkIndexAsc("kb_test_123"))
                .thenReturn(List.of(chunk1, chunk2));

        RetrievalRequest req = RetrievalRequest.builder()
                .knowledgeBaseId("kb_test_123")
                .query("退款多久到账？")
                .topK(3)
                .scoreThreshold(0.1)
                .searchMethod("hybrid_search")
                .rerankEnabled(true)
                .vectorWeight(0.7)
                .keywordWeight(0.3)
                .build();

        List<RetrievedChunk> chunks = provider.retrieve("kb_test_123", req);

        assertThat(chunks).isNotEmpty();
        // chunk1 应该排名在 chunk2 前面
        assertThat(chunks.get(0).chunkId()).isEqualTo("c1");
        assertThat(chunks.get(0).score()).isGreaterThan(0.0);
    }
}
