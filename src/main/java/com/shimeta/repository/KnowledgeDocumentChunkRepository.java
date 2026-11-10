package com.shimeta.repository;

import com.shimeta.model.KnowledgeDocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeDocumentChunkRepository extends JpaRepository<KnowledgeDocumentChunk, String> {

    /** 只取计算关键词分数要用的字段，不加载向量 */
    interface ChunkText {
        String getId();
        String getContent();
        String getContextTitle();
    }

    @Query("SELECT c.id AS id, c.content AS content, c.contextTitle AS contextTitle FROM KnowledgeDocumentChunk c "
            + "WHERE c.knowledgeBaseId = :knowledgeBaseId AND c.enabled = TRUE")
    List<ChunkText> findTextByKnowledgeBaseId(@Param("knowledgeBaseId") String knowledgeBaseId);

    /** 知识库启用切片的概况：任何切片新增、删除、启停或内容变化都会让它变化，BM25 索引据此判断要不要重建 */
    interface ChunkFingerprint {
        Long getCount();
        Long getTotalLength();
        String getMinId();
        String getMaxId();
    }

    @Query("SELECT COUNT(c) AS count, COALESCE(SUM(LENGTH(c.content) + COALESCE(LENGTH(c.contextTitle), 0)), 0) AS totalLength, "
            + "MIN(c.id) AS minId, MAX(c.id) AS maxId FROM KnowledgeDocumentChunk c "
            + "WHERE c.knowledgeBaseId = :knowledgeBaseId AND c.enabled = TRUE")
    ChunkFingerprint fingerprintByKnowledgeBaseId(@Param("knowledgeBaseId") String knowledgeBaseId);

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
