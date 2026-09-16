package com.example.agentplatform.repository;

import com.example.agentplatform.model.DifyConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DifyConfigRepository extends JpaRepository<DifyConfig, String> {

    Optional<DifyConfig> findFirstByIsActiveTrue();

    List<DifyConfig> findAllByOrderByCreatedAtDesc();

    @Modifying
    @Query("UPDATE DifyConfig d SET d.isActive = false WHERE d.id != :activeId")
    void deactivateOthers(String activeId);

    @Modifying
    @Query("UPDATE DifyConfig d SET d.isActive = false")
    void deactivateAll();
}
