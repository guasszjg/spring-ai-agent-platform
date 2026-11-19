package com.shimeta.repository;

import com.shimeta.model.KnowledgeRetrievalCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeRetrievalCacheRepository extends JpaRepository<KnowledgeRetrievalCache, String> {

    @Query("""
            SELECT c FROM KnowledgeRetrievalCache c
            WHERE c.knowledgeBaseId = :kbId AND (c.expiresAt IS NULL OR c.expiresAt >= :now)
            """)
    List<KnowledgeRetrievalCache> findActive(@Param("kbId") String kbId, @Param("now") LocalDateTime now);

    @Query(value = """
            SELECT * FROM knowledge_retrieval_cache
            WHERE knowledge_base_id = :kbId AND md5(query_text) = md5(:queryText) AND query_text = :queryText
              AND (expires_at IS NULL OR expires_at >= :now)
            LIMIT 2
            """, nativeQuery = true)
    List<KnowledgeRetrievalCache> findActiveExact(@Param("kbId") String kbId, @Param("queryText") String queryText,
                                                   @Param("now") LocalDateTime now);

    @Query(value = """
            WITH written AS (
                INSERT INTO knowledge_retrieval_cache AS cached
                    (id, knowledge_base_id, index_version_id, query_text, query_vector, result_json, created_at, expires_at)
                VALUES (:id, :kbId, :indexVersionId, :queryText, :queryVector, :resultJson, :now, :expiresAt)
                ON CONFLICT (knowledge_base_id, (md5(query_text))) DO UPDATE
                SET index_version_id = EXCLUDED.index_version_id, query_vector = EXCLUDED.query_vector,
                    result_json = EXCLUDED.result_json, created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at
                WHERE cached.query_text = EXCLUDED.query_text
                RETURNING id
            )
            SELECT id FROM written
            """, nativeQuery = true)
    Optional<String> upsert(@Param("id") String id, @Param("kbId") String kbId,
                            @Param("indexVersionId") String indexVersionId, @Param("queryText") String queryText,
                            @Param("queryVector") String queryVector, @Param("resultJson") String resultJson,
                            @Param("now") LocalDateTime now, @Param("expiresAt") LocalDateTime expiresAt);

    @Modifying
    @Query("""
            UPDATE KnowledgeRetrievalCache c
            SET c.hitCount = COALESCE(c.hitCount, 1) + 1,
                c.latencySavedMs = COALESCE(c.latencySavedMs, 0) + :latencySaved
            WHERE c.id = :id
            """)
    int incrementHit(@Param("id") String id, @Param("latencySaved") long latencySaved);

    @Modifying
    @Query("DELETE FROM KnowledgeRetrievalCache c WHERE c.knowledgeBaseId = :kbId")
    void deleteByKnowledgeBaseId(@Param("kbId") String kbId);

    long countByKnowledgeBaseId(String knowledgeBaseId);

    @Query("SELECT COALESCE(SUM(c.hitCount), 0) FROM KnowledgeRetrievalCache c WHERE c.knowledgeBaseId = :kbId")
    long sumHitCountByKnowledgeBaseId(@Param("kbId") String kbId);

    @Query("SELECT COALESCE(SUM(c.latencySavedMs), 0) FROM KnowledgeRetrievalCache c WHERE c.knowledgeBaseId = :kbId")
    long sumLatencySavedMsByKnowledgeBaseId(@Param("kbId") String kbId);

    @Modifying
    @Query("DELETE FROM KnowledgeRetrievalCache c WHERE c.expiresAt IS NOT NULL AND c.expiresAt < :now")
    void deleteExpired(@Param("now") LocalDateTime now);
}
