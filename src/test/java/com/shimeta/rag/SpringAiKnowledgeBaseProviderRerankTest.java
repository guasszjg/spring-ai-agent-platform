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

    private static KnowledgeDocumentChunkRepository.ChunkText text(String id, String content) {
        return new KnowledgeDocumentChunkRepository.ChunkText() {
            @Override
            public String getId() { return id; }

            @Override
            public String getContent() { return content; }
        };
    }

    /** 向量排 a、b 两个切片，只有 b 含有问题里的词；数据库的关键词查询按传入结果返回 */
    private SpringAiKnowledgeBaseProvider providerWithPg(com.shimeta.rag.pipeline.PgChunkSearch pg, List<com.shimeta.rag.pipeline.PgChunkSearch.Hit> keywordHits) {
        LocalEmbeddingService embeddingService = new LocalEmbeddingService(null);
        KnowledgeDocumentChunk a = chunk("a", "打印机墨盒更换说明", embeddingService);
        KnowledgeDocumentChunk b = chunk("b", "退款流程说明", embeddingService);
        when(pg.vectorSearch(anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new com.shimeta.rag.pipeline.PgChunkSearch.Hit("a", 0.1), new com.shimeta.rag.pipeline.PgChunkSearch.Hit("b", 0.2)));
        when(pg.keywordSearch(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(keywordHits);
        when(chunkRepository.findAllById(any())).thenReturn(List.of(a, b));
        when(chunkRepository.findTextByKnowledgeBaseId(KB_ID)).thenReturn(List.of(text("a", a.getContent()), text("b", b.getContent())));
        return new SpringAiKnowledgeBaseProvider(chunkRepository, knowledgeBaseRepository, new DocumentChunker(),
                embeddingService, new KeywordRetriever(), new QueryTransformer(null), new ContextBudgetPruner(),
                null, null, null, pg, null);
    }

    private static RetrievalRequest hybridRequest(String method, double vectorWeight, double keywordWeight) {
        return RetrievalRequest.builder().knowledgeBaseId(KB_ID).query("退款流程怎么走").topK(3).scoreThreshold(0.0)
                .searchMethod(method).vectorWeight(vectorWeight).keywordWeight(keywordWeight).build();
    }

    @Test
    void hybridFillsKeywordRouteWhenDatabaseFindsNothing() {
        com.shimeta.rag.pipeline.PgChunkSearch pg = org.mockito.Mockito.mock(com.shimeta.rag.pipeline.PgChunkSearch.class);
        SpringAiKnowledgeBaseProvider withPg = providerWithPg(pg, List.of());

        List<RetrievedChunk> keywordHeavy = withPg.retrieve(KB_ID, hybridRequest("hybrid_search", 0.1, 0.9));

        assertThat(keywordHeavy).extracting(RetrievedChunk::chunkId).containsExactly("b", "a");
        assertThat(keywordHeavy.get(0).keywordScore()).isGreaterThan(0.2);
        // 切片 a 没有问题里的词，不进关键词这一路，权重全给向量时排序回到向量结果
        assertThat(withPg.retrieve(KB_ID, hybridRequest("hybrid_search", 1.0, 0.0)))
                .extracting(RetrievedChunk::chunkId).containsExactly("a", "b");
    }

    @Test
    void databaseKeywordHitsAreUsedWithoutScanningChunks() {
        com.shimeta.rag.pipeline.PgChunkSearch pg = org.mockito.Mockito.mock(com.shimeta.rag.pipeline.PgChunkSearch.class);
        SpringAiKnowledgeBaseProvider withPg = providerWithPg(pg, List.of(new com.shimeta.rag.pipeline.PgChunkSearch.Hit("a", 1.0)));

        assertThat(withPg.retrieve(KB_ID, hybridRequest("hybrid_search", 0.1, 0.9)).get(0).chunkId()).isEqualTo("a");
        verify(chunkRepository, never()).findTextByKnowledgeBaseId(anyString());
    }

    @Test
    void otherSearchMethodsDoNotScanChunks() {
        com.shimeta.rag.pipeline.PgChunkSearch pg = org.mockito.Mockito.mock(com.shimeta.rag.pipeline.PgChunkSearch.class);
        SpringAiKnowledgeBaseProvider withPg = providerWithPg(pg, List.of());

        withPg.retrieve(KB_ID, hybridRequest("semantic_search", 0.7, 0.3));

        verify(chunkRepository, never()).findTextByKnowledgeBaseId(anyString());
    }

    @Test
    void fullTextSearchUsesKeywordRouteOnly() {
        RetrievalRequest.Builder builder = RetrievalRequest.builder()
                .knowledgeBaseId(KB_ID).query("流程").topK(3).scoreThreshold(0.5).cacheEnabled(false);

        List<RetrievedChunk> fullText = provider.retrieve(KB_ID, builder.searchMethod("full_text_search").build());
        assertThat(fullText).extracting(RetrievedChunk::chunkId).containsExactly("b");
        assertThat(fullText.get(0).matchType()).isEqualTo("KEYWORD");

        assertThat(provider.retrieve(KB_ID, builder.searchMethod("hybrid_search").build())).hasSize(2);
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
