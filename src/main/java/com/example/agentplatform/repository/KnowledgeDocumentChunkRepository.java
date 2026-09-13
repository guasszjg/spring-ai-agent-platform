package com.example.agentplatform.repository;

import com.example.agentplatform.model.KnowledgeDocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeDocumentChunkRepository extends JpaRepository<KnowledgeDocumentChunk, String> {

    List<KnowledgeDocumentChunk> findByKnowledgeBaseIdAndEnabledTrueOrderByChunkIndexAsc(String knowledgeBaseId);

    List<KnowledgeDocumentChunk> findByDocumentIdOrderByChunkIndexAsc(String documentId);

    List<KnowledgeDocumentChunk> findByFaqId(String faqId);

    @Modifying
    @Query("DELETE FROM KnowledgeDocumentChunk c WHERE c.documentId = :documentId")
    void deleteByDocumentId(@Param("documentId") String documentId);

    @Modifying
    @Query("DELETE FROM KnowledgeDocumentChunk c WHERE c.faqId = :faqId")
    void deleteByFaqId(@Param("faqId") String faqId);

    @Modifying
    @Query("DELETE FROM KnowledgeDocumentChunk c WHERE c.knowledgeBaseId = :knowledgeBaseId")
    void deleteByKnowledgeBaseId(@Param("knowledgeBaseId") String knowledgeBaseId);

    long countByKnowledgeBaseId(String knowledgeBaseId);

    long countByKnowledgeBaseIdAndEnabledTrue(String knowledgeBaseId);

    long countByDocumentId(String documentId);
}
