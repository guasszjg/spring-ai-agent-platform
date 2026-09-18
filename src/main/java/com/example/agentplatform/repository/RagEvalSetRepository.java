package com.example.agentplatform.repository;

import com.example.agentplatform.model.RagEvalSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RagEvalSetRepository extends JpaRepository<RagEvalSet, String> {
    Optional<RagEvalSet> findFirstByKnowledgeBaseIdAndName(String knowledgeBaseId, String name);
    List<RagEvalSet> findByKnowledgeBaseIdOrderByCreatedAtDesc(String knowledgeBaseId);
}
