package com.example.agentplatform.repository;

import com.example.agentplatform.model.KnowledgeFaq;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeFaqRepository extends JpaRepository<KnowledgeFaq, String> {

    List<KnowledgeFaq> findByKnowledgeBaseId(String knowledgeBaseId);

    Page<KnowledgeFaq> findByKnowledgeBaseId(String knowledgeBaseId, Pageable pageable);

    @Query("SELECT f FROM KnowledgeFaq f WHERE f.knowledgeBaseId = :kbId AND " +
           "(:keyword IS NULL OR :keyword = '' OR LOWER(f.question) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(f.answer) LIKE LOWER(CONCAT('%', :keyword, '%'))) AND " +
           "(:category IS NULL OR :category = '' OR :category = '全部' OR f.category = :category)")
    Page<KnowledgeFaq> searchFaqs(@Param("kbId") String kbId,
                                  @Param("keyword") String keyword,
                                  @Param("category") String category,
                                  Pageable pageable);

    @Query("SELECT DISTINCT f.category FROM KnowledgeFaq f WHERE f.knowledgeBaseId = :kbId AND f.category IS NOT NULL")
    List<String> findDistinctCategoriesByKnowledgeBaseId(@Param("kbId") String kbId);

    Optional<KnowledgeFaq> findByExternalDocId(String externalDocId);

    long countByKnowledgeBaseId(String knowledgeBaseId);

    void deleteByKnowledgeBaseId(String knowledgeBaseId);
}
