package com.example.agentplatform.repository;

import com.example.agentplatform.model.KnowledgeIndexJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeIndexJobRepository extends JpaRepository<KnowledgeIndexJob, String> {

    List<KnowledgeIndexJob> findByIndexVersionIdOrderByCreatedAtDesc(String indexVersionId);

    List<KnowledgeIndexJob> findByKnowledgeBaseIdOrderByCreatedAtDesc(String knowledgeBaseId);

    Optional<KnowledgeIndexJob> findTopByIndexVersionIdOrderByCreatedAtDesc(String indexVersionId);

    List<KnowledgeIndexJob> findByStatus(String status);
}
