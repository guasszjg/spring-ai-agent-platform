package com.example.agentplatform.repository;

import com.example.agentplatform.model.OcrConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OcrConfigRepository extends JpaRepository<OcrConfig, String> {

    Optional<OcrConfig> findFirstByIsActiveTrue();

    List<OcrConfig> findAllByOrderByCreatedAtDesc();

    @Modifying
    @Query("UPDATE OcrConfig e SET e.isActive = false WHERE e.id != :activeId")
    void deactivateOthers(String activeId);

    @Modifying
    @Query("UPDATE OcrConfig e SET e.isActive = false")
    void deactivateAll();
}
