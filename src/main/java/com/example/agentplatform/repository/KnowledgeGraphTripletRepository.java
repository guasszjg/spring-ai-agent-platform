package com.example.agentplatform.repository;

import com.example.agentplatform.model.KnowledgeGraphTriplet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeGraphTripletRepository extends JpaRepository<KnowledgeGraphTriplet, String> {

    List<KnowledgeGraphTriplet> findByKnowledgeBaseId(String knowledgeBaseId);

    List<KnowledgeGraphTriplet> findByKnowledgeBaseIdAndSourceEntityOrKnowledgeBaseIdAndTargetEntity(
            String knowledgeBaseId1, String sourceEntity, String knowledgeBaseId2, String targetEntity);

    @Query("SELECT t FROM KnowledgeGraphTriplet t WHERE t.knowledgeBaseId = :kbId AND " +
           "(LOWER(t.sourceEntity) LIKE LOWER(CONCAT('%', :kw, '%')) OR LOWER(t.targetEntity) LIKE LOWER(CONCAT('%', :kw, '%')))")
    List<KnowledgeGraphTriplet> searchByEntityKeyword(@Param("kbId") String kbId, @Param("kw") String kw);

    @Modifying
    @Query("DELETE FROM KnowledgeGraphTriplet t WHERE t.knowledgeBaseId = :kbId")
    void deleteByKnowledgeBaseId(@Param("kbId") String kbId);

    long countByKnowledgeBaseId(String knowledgeBaseId);
}
