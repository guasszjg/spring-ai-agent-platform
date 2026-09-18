package com.example.agentplatform.repository;

import com.example.agentplatform.model.RagEvalRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagEvalRunRepository extends JpaRepository<RagEvalRun, String> {
    List<RagEvalRun> findByKnowledgeBaseIdOrderByCreatedAtDesc(String knowledgeBaseId);
}
