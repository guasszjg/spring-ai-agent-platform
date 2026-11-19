package com.shimeta.rag.cache;

import com.shimeta.model.KnowledgeRetrievalCache;
import com.shimeta.rag.RetrievedChunk;
import com.shimeta.rag.pipeline.LocalEmbeddingService;
import com.shimeta.repository.KnowledgeRetrievalCacheRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAG 语义缓存服务 (Semantic Cache Service)
 * 遵循《Spring-AI自研RAG双引擎设计.md》第 10 章与第 18 章
 *
 * 核心特性：
 * 1. 快速精准匹配 (< 1ms)：完全相同的 Query 文本直接瞬时返回
 * 2. 向量语义匹配 (< 5ms)：计算 Query 嵌入余弦相似度，若相似度 >= 0.95 则判定语义相同直接复用
 * 3. 节约 100% 检索、重排与下游模型 Token 成本，并在结果中携带 semantic_cache_hit 徽标
 * 4. 知识库版本切换或文档重新切分时自动失效
 */
@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private final KnowledgeRetrievalCacheRepository cacheRepository;
    private final LocalEmbeddingService embeddingService;
    // RetrievedChunk 的 isImage()/isGraph() 会被写成 image/graph 字段，读回时要忽略，否则整条缓存解析失败
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // 内存一级快速缓存 (kbId -> Map<normalizedQuery, CachedItem>)
    private final Map<String, Map<String, CachedMemoryItem>> memoryCache = new ConcurrentHashMap<>();

    @Autowired
    public SemanticCacheService(KnowledgeRetrievalCacheRepository cacheRepository,
                                LocalEmbeddingService embeddingService) {
        this.cacheRepository = cacheRepository;
        this.embeddingService = embeddingService;
    }

    public record CacheLookupResult(
            List<RetrievedChunk> chunks,
            boolean isHit,
            double similarity,
            long hitCount,
            long savedLatencyMs
    ) {}

    public record CacheStats(
            String knowledgeBaseId,
            long totalCachedQueries,
            long totalHitCount,
            long totalLatencySavedMs,
            long estimatedTokensSaved
    ) {}

    private record CachedMemoryItem(
            String id,
            List<RetrievedChunk> chunks,
            long hitCount,
            LocalDateTime expiresAt
    ) {}

    /**
     * 语义缓存查询
     *
     * @param kbId 知识库标识
     * @param query 查询文本
     * @param queryVector 查询向量（可为空，为空时先尝试精确匹配）
     * @param similarityThreshold 语义相似度判定阈值（默认建议 0.95）
     * @return 缓存命中结果
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<CacheLookupResult> lookup(String kbId, String query, float[] queryVector, double similarityThreshold) {
        if (kbId == null || query == null || query.isBlank()) {
            return Optional.empty();
        }

        String normalizedQuery = normalize(query);
        LocalDateTime now = LocalDateTime.now();

        // 1. 一级内存快速精确匹配
        Map<String, CachedMemoryItem> kbMem = memoryCache.get(kbId);
        if (kbMem != null) {
            CachedMemoryItem memItem = kbMem.get(normalizedQuery);
            if (memItem != null && !isExpired(memItem.expiresAt())
                    && cacheRepository.incrementHit(memItem.id(), 150L) > 0) {
                long newHit = memItem.hitCount() + 1;
                List<RetrievedChunk> annotated = annotateChunks(memItem.chunks(), 1.0, newHit);
                log.info("语义缓存[内存精确命中]: kbId={}, query='{}', hitCount={}", kbId, query, newHit);
                return Optional.of(new CacheLookupResult(annotated, true, 1.0, newHit, 150L));
            }
            if (memItem != null) {
                kbMem.remove(normalizedQuery, memItem);
            }
        }

        // 2. 二级持久化精确匹配
        List<KnowledgeRetrievalCache> exactMatches = cacheRepository.findActiveExact(kbId, normalizedQuery, now);
        if (exactMatches.size() > 1) {
            log.warn("Duplicate active retrieval cache entries: kbId={}", kbId);
            return Optional.empty();
        }
        if (!exactMatches.isEmpty()) {
            return useDatabaseHit(kbId, normalizedQuery, exactMatches.getFirst(), 1.0, 120L);
        }

        // 3. 向量语义相似度匹配 (余弦相似度 >= similarityThreshold)
        List<KnowledgeRetrievalCache> allCached = cacheRepository.findActive(kbId, now);
        if (allCached.isEmpty()) {
            return Optional.empty();
        }
        if (queryVector == null || queryVector.length == 0) {
            queryVector = embeddingService.embed(query);
        }

        KnowledgeRetrievalCache bestMatch = null;
        double highestSimilarity = -1.0;

        for (KnowledgeRetrievalCache item : allCached) {
            if (item.getQueryVector() == null || item.getQueryVector().isBlank()) {
                continue;
            }
            float[] cachedVec = embeddingService.deserializeVector(item.getQueryVector());
            if (cachedVec.length == 0) {
                continue;
            }
            double sim = embeddingService.cosineSimilarity(queryVector, cachedVec);
            if (sim > highestSimilarity) {
                highestSimilarity = sim;
                bestMatch = item;
            }
        }

        double effectiveThreshold = similarityThreshold > 0 ? similarityThreshold : 0.95;
        if (bestMatch != null && highestSimilarity >= effectiveThreshold) {
            return useDatabaseHit(kbId, normalizedQuery, bestMatch, highestSimilarity, 180L);
        }

        return Optional.empty();
    }

    private Optional<CacheLookupResult> useDatabaseHit(String kbId, String query, KnowledgeRetrievalCache entry,
                                                       double similarity, long latencySaved) {
        List<RetrievedChunk> chunks = deserializeChunks(entry.getResultJson());
        if (chunks == null || chunks.isEmpty() || chunks.stream().anyMatch(chunk -> chunk == null)) {
            return Optional.empty();
        }

        long newHit = (entry.getHitCount() != null ? entry.getHitCount() : 1L) + 1;
        if (cacheRepository.incrementHit(entry.getId(), latencySaved) == 0) {
            return Optional.empty();
        }
        if (similarity == 1.0 && query.equals(entry.getQueryText())) {
            putMemory(kbId, query, entry.getId(), chunks, newHit, entry.getExpiresAt());
        }
        return Optional.of(new CacheLookupResult(annotateChunks(chunks, similarity, newHit),
                true, similarity, newHit, latencySaved));
    }

    /**
     * 写入语义缓存
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void put(String kbId, String indexVersionId, String query, float[] queryVector,
                    List<RetrievedChunk> chunks, long executionLatencyMs, Long ttlSeconds) {
        if (kbId == null || query == null || query.isBlank() || chunks == null || chunks.isEmpty()) {
            return;
        }

        String normalizedQuery = normalize(query);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = ttlSeconds != null && ttlSeconds > 0
                ? now.plusSeconds(ttlSeconds) : now.plusDays(7);
        float[] vector = queryVector != null && queryVector.length > 0 ? queryVector : embeddingService.embed(query);
        String serializedVector = embeddingService.serializeVector(vector);
        String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(chunks);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize retrieval cache chunks", e);
        }

        cacheRepository.upsert(UUID.randomUUID().toString(), kbId, indexVersionId, normalizedQuery,
                        serializedVector, resultJson, now, expiresAt)
                .ifPresent(id -> putMemory(kbId, normalizedQuery, id, chunks, 1L, expiresAt));
    }

    /**
     * 清空指定知识库的所有语义缓存 (在文档更新、重新切分或版本切换时触发)
     */
    @Transactional
    public void clearCache(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            return;
        }
        memoryCache.remove(kbId);
        cacheRepository.deleteByKnowledgeBaseId(kbId);
        log.info("已清空知识库语义缓存: kbId={}", kbId);
    }

    /** 清空全部知识库的语义缓存（切换生效的 Rerank 模型后，已缓存的排序不再准确） */
    @Transactional
    public void clearAll() {
        memoryCache.clear();
        cacheRepository.deleteAllInBatch();
        log.info("已清空全部知识库语义缓存");
    }

    /**
     * 获取知识库语义缓存统计信息
     */
    @Transactional(readOnly = true)
    public CacheStats getCacheStats(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            return new CacheStats(kbId, 0, 0, 0, 0);
        }
        long count = cacheRepository.countByKnowledgeBaseId(kbId);
        long hits = cacheRepository.sumHitCountByKnowledgeBaseId(kbId);
        long latencySaved = cacheRepository.sumLatencySavedMsByKnowledgeBaseId(kbId);
        // 预估节约的 token：每次命中平均节约 1500 tokens
        long tokensSaved = hits * 1500L;
        return new CacheStats(kbId, count, hits, latencySaved, tokensSaved);
    }

    private void putMemory(String kbId, String query, String id, List<RetrievedChunk> chunks,
                           long hitCount, LocalDateTime expiresAt) {
        Runnable store = () -> memoryCache.computeIfAbsent(kbId, key -> new ConcurrentHashMap<>())
                .put(query, new CachedMemoryItem(id, chunks, hitCount, expiresAt));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    store.run();
                }
            });
        } else {
            store.run();
        }
    }

    private List<RetrievedChunk> annotateChunks(List<RetrievedChunk> original, double similarity, long hitCount) {
        List<RetrievedChunk> res = new ArrayList<>();
        for (RetrievedChunk c : original) {
            Map<String, Object> meta = c.metadata() != null ? new HashMap<>(c.metadata()) : new HashMap<>();
            meta.put("semanticCacheHit", true);
            meta.put("cacheSimilarity", Math.round(similarity * 10000.0) / 10000.0);
            meta.put("cacheHitCount", hitCount);
            RetrievedChunk copy = RetrievedChunk.builder()
                    .chunkId(c.chunkId())
                    .documentId(c.documentId())
                    .parentChunkId(c.parentChunkId())
                    .sourceName(c.sourceName())
                    .sourceUrl(c.sourceUrl())
                    .pageNumber(c.pageNumber())
                    .startOffset(c.startOffset())
                    .endOffset(c.endOffset())
                    .content(c.content())
                    .rawContent(c.rawContent())
                    .score(c.score())
                    .vectorScore(c.vectorScore())
                    .keywordScore(c.keywordScore())
                    .rerankScore(c.rerankScore())
                    .matchType("CACHE")
                    .tokenCount(c.tokenCount())
                    .imageUrl(c.imageUrl())
                    .imageCaption(c.imageCaption())
                    .matchedBy(c.matchedBy())
                    .chunkType(c.chunkType())
                    .metadata(meta)
                    .build();
            res.add(copy);
        }
        return res;
    }

    /** 解析失败时由调用方继续正常检索。 */
    private List<RetrievedChunk> deserializeChunks(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<RetrievedChunk>>() {});
        } catch (Exception e) {
            log.warn("反序列化缓存切片失败，按未命中处理: {}", e.getMessage());
            return null;
        }
    }

    private boolean isExpired(LocalDateTime expiresAt) {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    private String normalize(String q) {
        return q == null ? "" : q.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
