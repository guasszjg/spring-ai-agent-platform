package com.example.agentplatform.repository;

import com.example.agentplatform.model.RagEvalCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagEvalCaseRepository extends JpaRepository<RagEvalCase, String> {
    List<RagEvalCase> findBySetIdOrderByCreatedAtAsc(String setId);
    List<RagEvalCase> findByKnowledgeBaseIdOrderByCreatedAtDesc(String knowledgeBaseId);
}
