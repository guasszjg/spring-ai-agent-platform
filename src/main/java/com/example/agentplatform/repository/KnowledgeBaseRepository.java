package com.example.agentplatform.repository;

import com.example.agentplatform.model.KnowledgeBase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, String> {

    Optional<KnowledgeBase> findByExternalDatasetId(String externalDatasetId);

    @Query("SELECT k FROM KnowledgeBase k WHERE (:keyword IS NULL OR :keyword = '' OR " +
           "LOWER(k.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(k.description) LIKE LOWER(CONCAT('%', :keyword, '%'))) AND " +
           "(:provider IS NULL OR :provider = '' OR k.provider = :provider)")
    Page<KnowledgeBase> searchKnowledgeBases(@Param("keyword") String keyword,
                                            @Param("provider") String provider,
                                            Pageable pageable);
}
