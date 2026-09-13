package com.example.agentplatform.repository;

import com.example.agentplatform.model.KnowledgeRetrievalCache;
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

    List<KnowledgeRetrievalCache> findByKnowledgeBaseId(String knowledgeBaseId);

    Optional<KnowledgeRetrievalCache> findByKnowledgeBaseIdAndQueryText(String knowledgeBaseId, String queryText);

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
