package com.example.agentplatform.repository;

import com.example.agentplatform.model.EmbeddingConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmbeddingConfigRepository extends JpaRepository<EmbeddingConfig, String> {

    Optional<EmbeddingConfig> findFirstByIsActiveTrue();

    List<EmbeddingConfig> findAllByOrderByCreatedAtDesc();

    @Modifying
    @Query("UPDATE EmbeddingConfig e SET e.isActive = false WHERE e.id != :activeId")
    void deactivateOthers(String activeId);

    @Modifying
    @Query("UPDATE EmbeddingConfig e SET e.isActive = false")
    void deactivateAll();
}
