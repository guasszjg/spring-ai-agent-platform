package com.example.agentplatform.repository;

import com.example.agentplatform.model.KnowledgeSourceRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeSourceRevisionRepository extends JpaRepository<KnowledgeSourceRevision, String> {

    List<KnowledgeSourceRevision> findByKnowledgeBaseIdOrderByCreatedAtDesc(String knowledgeBaseId);

    List<KnowledgeSourceRevision> findByDocumentIdOrderByRevisionNoDesc(String documentId);

    Optional<KnowledgeSourceRevision> findByDocumentIdAndRevisionNo(String documentId, Integer revisionNo);

    Optional<KnowledgeSourceRevision> findTopByDocumentIdOrderByRevisionNoDesc(String documentId);
}
