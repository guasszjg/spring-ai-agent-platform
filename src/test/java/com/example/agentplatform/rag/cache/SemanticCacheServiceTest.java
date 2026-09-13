package com.example.agentplatform.rag.cache;

import com.example.agentplatform.model.KnowledgeRetrievalCache;
import com.example.agentplatform.rag.RetrievedChunk;
import com.example.agentplatform.rag.pipeline.LocalEmbeddingService;
import com.example.agentplatform.repository.KnowledgeRetrievalCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class SemanticCacheServiceTest {

    private KnowledgeRetrievalCacheRepository cacheRepository;
    private LocalEmbeddingService embeddingService;
    private SemanticCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheRepository = Mockito.mock(KnowledgeRetrievalCacheRepository.class);
        embeddingService = new LocalEmbeddingService(null);
        cacheService = new SemanticCacheService(cacheRepository, embeddingService);
    }

    @Test
    void testExactQueryHitInDatabase() {
        String kbId = "kb_test";
        String query = "如何重置密码";
        RetrievedChunk sampleChunk = RetrievedChunk.builder()
                .chunkId("c1")
                .content("请前往个人中心修改密码")
                .sourceName("用户手册.txt")
                .score(0.9)
                .build();

        KnowledgeRetrievalCache cacheEntity = new KnowledgeRetrievalCache();
        cacheEntity.setId("cache_1");
        cacheEntity.setKnowledgeBaseId(kbId);
        cacheEntity.setQueryText(query.trim().toLowerCase());
        cacheEntity.setQueryVector(embeddingService.serializeVector(embeddingService.embed(query, 1024)));
        cacheEntity.setResultJson("[{\"chunkId\":\"c1\",\"content\":\"请前往个人中心修改密码\",\"sourceName\":\"用户手册.txt\",\"score\":0.9,\"matchType\":\"VECTOR\"}]");
        cacheEntity.setHitCount(2L);
        cacheEntity.setLatencySavedMs(200L);
        cacheEntity.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(cacheRepository.findByKnowledgeBaseIdAndQueryText(eq(kbId), eq(query.trim().toLowerCase())))
                .thenReturn(Optional.of(cacheEntity));

        Optional<SemanticCacheService.CacheLookupResult> result = cacheService.lookup(kbId, query, null, 0.95);

        assertTrue(result.isPresent());
        assertTrue(result.get().isHit());
        assertEquals(1.0, result.get().similarity());
        assertEquals(3L, result.get().hitCount());
        assertFalse(result.get().chunks().isEmpty());
        assertTrue(result.get().chunks().get(0).metadata().containsKey("semanticCacheHit"));
        assertEquals(true, result.get().chunks().get(0).metadata().get("semanticCacheHit"));

        verify(cacheRepository, atLeastOnce()).save(any(KnowledgeRetrievalCache.class));
    }

    @Test
    void testVectorCosineSimilarityHit() {
        String kbId = "kb_test";
        String cachedQuery = "Spring AI 如何配置本地模型";
        String incomingQuery = "Spring AI 怎么配置本地模型"; // 语义高度相似

        float[] cachedVec = embeddingService.embed(cachedQuery, 1024);

        KnowledgeRetrievalCache cacheEntity = new KnowledgeRetrievalCache();
        cacheEntity.setId("cache_sim");
        cacheEntity.setKnowledgeBaseId(kbId);
        cacheEntity.setQueryText(cachedQuery);
        cacheEntity.setQueryVector(embeddingService.serializeVector(cachedVec));
        cacheEntity.setResultJson("[{\"chunkId\":\"c2\",\"content\":\"可在 application.properties 中配置\",\"sourceName\":\"spring-ai.md\",\"score\":0.88}]");
        cacheEntity.setHitCount(1L);
        cacheEntity.setExpiresAt(LocalDateTime.now().plusDays(2));

        when(cacheRepository.findByKnowledgeBaseIdAndQueryText(eq(kbId), any()))
                .thenReturn(Optional.empty());
        when(cacheRepository.findByKnowledgeBaseId(eq(kbId)))
                .thenReturn(List.of(cacheEntity));

        float[] incomingVec = embeddingService.embed(incomingQuery, 1024);
        Optional<SemanticCacheService.CacheLookupResult> result = cacheService.lookup(kbId, incomingQuery, incomingVec, 0.85);

        assertTrue(result.isPresent());
        assertTrue(result.get().isHit());
        assertTrue(result.get().similarity() >= 0.85);
        assertEquals(2L, result.get().hitCount());
    }

    @Test
    void testPutAndClearCache() {
        String kbId = "kb_demo";
        String query = "什么是 RAG";
        RetrievedChunk chunk = RetrievedChunk.builder().content("检索增强生成").score(0.95).build();

        cacheService.put(kbId, "v1", query, null, List.of(chunk), 120L, 3600L);
        verify(cacheRepository, times(1)).save(any(KnowledgeRetrievalCache.class));

        cacheService.clearCache(kbId);
        verify(cacheRepository, times(1)).deleteByKnowledgeBaseId(eq(kbId));
    }
}
