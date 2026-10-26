package com.shimeta.repository;

import com.shimeta.model.KnowledgeIndexVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeIndexVersionRepository extends JpaRepository<KnowledgeIndexVersion, String> {

    List<KnowledgeIndexVersion> findByKnowledgeBaseIdOrderByVersionNoDesc(String knowledgeBaseId);

    Optional<KnowledgeIndexVersion> findByKnowledgeBaseIdAndVersionNo(String knowledgeBaseId, Integer versionNo);

    Optional<KnowledgeIndexVersion> findByKnowledgeBaseIdAndStatus(String knowledgeBaseId, String status);

    long countByKnowledgeBaseId(String knowledgeBaseId);

    long countByKnowledgeBaseIdAndStatus(String knowledgeBaseId, String status);
}
