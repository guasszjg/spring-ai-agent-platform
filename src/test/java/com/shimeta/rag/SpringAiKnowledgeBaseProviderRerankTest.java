package com.shimeta.rag;

import com.shimeta.model.KnowledgeBase;
import com.shimeta.model.KnowledgeDocumentChunk;
import com.shimeta.model.RerankConfig;
import com.shimeta.rag.cache.SemanticCacheService;
import com.shimeta.rag.engine.RetrievalRequest;
import com.shimeta.rag.pipeline.ContextBudgetPruner;
import com.shimeta.rag.pipeline.DocumentChunker;
import com.shimeta.rag.pipeline.KeywordRetriever;
import com.shimeta.rag.pipeline.LocalEmbeddingService;
import com.shimeta.rag.pipeline.QueryTransformer;
import com.shimeta.repository.KnowledgeBaseRepository;
import com.shimeta.repository.KnowledgeDocumentChunkRepository;
import com.shimeta.service.RerankConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpringAiKnowledgeBaseProviderRerankTest {

    private static final String KB_ID = "kb-rerank";

    @Mock private KnowledgeDocumentChunkRepository chunkRepository;
    @Mock private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock private RerankConfigService rerankConfigService;
    @Mock private SemanticCacheService semanticCacheService;

    private final RerankConfig config = new RerankConfig();
    private KnowledgeBase kb;
    private SpringAiKnowledgeBaseProvider provider;

    @BeforeEach
    void setUp() {
        LocalEmbeddingService embeddingService = new LocalEmbeddingService(null);
        provider = new SpringAiKnowledgeBaseProvider(chunkRepository, knowledgeBaseRepository, new DocumentChunker(),
                embeddingService, new KeywordRetriever(), new QueryTransformer(null), new ContextBudgetPruner(),
                null, semanticCacheService, null, null, rerankConfigService);

        config.setModelName("any-rerank-model");
        kb = new KnowledgeBase();
        kb.setId(KB_ID);
        when(knowledgeBaseRepository.findByExternalDatasetId(anyString())).thenReturn(Optional.empty());
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(kb));
        when(semanticCacheService.lookup(anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(Optional.empty());
        when(chunkRepository.findByKnowledgeBaseIdAndEnabledTrueOrderByChunkIndexAsc(KB_ID))
                .thenReturn(List.of(chunk("a", "退款多久到账", embeddingService), chunk("b", "退款流程说明", embeddingService)));
    }

    private static KnowledgeDocumentChunk chunk(String id, String content, LocalEmbeddingService embeddingService) {
        KnowledgeDocumentChunk chunk = new KnowledgeDocumentChunk();
        chunk.setId(id);
        chunk.setKnowledgeBaseId(KB_ID);
        chunk.setChunkIndex(0);
        chunk.setContent(content);
        chunk.setTokenCount(10L);
        chunk.setEnabled(true);
        chunk.setEmbedding(embeddingService.serializeVector(embeddingService.embed(content, 1024)));
        return chunk;
    }

    private RetrievalRequest request(String mode) {
        return RetrievalRequest.builder()
                .knowledgeBaseId(KB_ID)
                .query("退款多久到账")
                .topK(3)
                .scoreThreshold(0.0)
                .searchMethod("hybrid_search")
                .rerankEnabled(true)
                .rerankMode(mode)
                .build();
    }

    @Test
    void rerankModeOrdersByModelScoreAndRecordsRealUsage() {
        when(rerankConfigService.getActiveConfig()).thenReturn(Optional.of(config));
        // 融合排序里 a 在前，模型把 b 排到前面
        when(rerankConfigService.rerank(any(), anyString(), anyList())).thenReturn(new RerankConfigService.Result(
                List.of(new RerankConfigService.Hit(1, 0.9), new RerankConfigService.Hit(0, 0.2)), 321));

        List<RetrievedChunk> chunks = provider.retrieve(KB_ID, request("reranking_model"));

        assertThat(chunks).extracting(RetrievedChunk::chunkId).containsExactly("b", "a");
        assertThat(chunks.get(0).score()).isEqualTo(0.9);
        assertThat(chunks.get(0).rerankScore()).isEqualTo(0.9);
        assertThat(kb.getRerankCalls()).isEqualTo(1);
        assertThat(kb.getRerankTokens()).isEqualTo(321);
        verify(semanticCacheService).put(anyString(), any(), anyString(), any(), anyList(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void estimatesTokensWhenModelReturnsNoUsage() {
        when(rerankConfigService.getActiveConfig()).thenReturn(Optional.of(config));
        when(rerankConfigService.rerank(any(), anyString(), anyList())).thenReturn(new RerankConfigService.Result(
                List.of(new RerankConfigService.Hit(0, 0.9)), 0));

        provider.retrieve(KB_ID, request("reranking_model"));

        assertThat(kb.getRerankTokens()).isPositive();
    }

    @Test
    void weightedModeNeverCallsModelEvenWhenSwitchIsOn() {
        when(rerankConfigService.getActiveConfig()).thenReturn(Optional.of(config));

        List<RetrievedChunk> chunks = provider.retrieve(KB_ID, request("weighted_score"));

        verify(rerankConfigService, never()).rerank(any(), anyString(), anyList());
        assertThat(chunks).isNotEmpty().allSatisfy(c -> assertThat(c.rerankScore()).isNull());
        assertThat(kb.getRerankCalls()).isZero();
        assertThat(kb.getEstimatedCost()).isZero();
    }

    @Test
    void modelFailureFallsBackWithoutChargingOrCaching() {
        when(rerankConfigService.getActiveConfig()).thenReturn(Optional.of(config));
        when(rerankConfigService.rerank(any(), anyString(), anyList())).thenThrow(new IllegalStateException("timeout"));

        List<RetrievedChunk> chunks = provider.retrieve(KB_ID, request("reranking_model"));

        assertThat(chunks).isNotEmpty().allSatisfy(c -> assertThat(c.rerankScore()).isNull());
        assertThat(kb.getRerankCalls()).isZero();
        verify(semanticCacheService, never()).put(anyString(), any(), anyString(), any(), anyList(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void missingActiveConfigFallsBackWithoutCalling() {
        when(rerankConfigService.getActiveConfig()).thenReturn(Optional.empty());

        List<RetrievedChunk> chunks = provider.retrieve(KB_ID, request("reranking_model"));

        assertThat(chunks).isNotEmpty();
        verify(rerankConfigService, never()).rerank(any(), anyString(), anyList());
    }

    @Test
    void sendsOnlyTopCandidatesToModel() {
        LocalEmbeddingService embeddingService = new LocalEmbeddingService(null);
        List<KnowledgeDocumentChunk> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(chunk("c" + i, "退款说明第" + i + "条", embeddingService));
        }
        when(chunkRepository.findByKnowledgeBaseIdAndEnabledTrueOrderByChunkIndexAsc(KB_ID)).thenReturn(many);
        when(rerankConfigService.getActiveConfig()).thenReturn(Optional.of(config));
        when(rerankConfigService.rerank(any(), anyString(), anyList())).thenReturn(new RerankConfigService.Result(
                List.of(new RerankConfigService.Hit(0, 0.9)), 10));

        provider.retrieve(KB_ID, request("reranking_model"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> documents = ArgumentCaptor.forClass(List.class);
        verify(rerankConfigService).rerank(any(), anyString(), documents.capture());
        assertThat(documents.getValue()).hasSize(20);
    }
}
