package com.example.agentplatform.repository;

import com.example.agentplatform.model.KnowledgeDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, String> {

    List<KnowledgeDocument> findByKnowledgeBaseId(String knowledgeBaseId);

    Page<KnowledgeDocument> findByKnowledgeBaseId(String knowledgeBaseId, Pageable pageable);

    @Query("SELECT d FROM KnowledgeDocument d WHERE d.knowledgeBaseId = :kbId AND " +
           "(:keyword IS NULL OR :keyword = '' OR LOWER(d.name) LIKE LOWER(CONCAT('%', :keyword, '%'))) AND " +
           "(:status IS NULL OR :status = '' OR d.indexingStatus = :status)")
    Page<KnowledgeDocument> searchDocuments(@Param("kbId") String kbId,
                                            @Param("keyword") String keyword,
                                            @Param("status") String status,
                                            Pageable pageable);

    Optional<KnowledgeDocument> findByExternalDocId(String externalDocId);

    long countByKnowledgeBaseId(String knowledgeBaseId);

    void deleteByKnowledgeBaseId(String knowledgeBaseId);
}
