package com.shimeta.repository;

import com.shimeta.model.RerankConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RerankConfigRepository extends JpaRepository<RerankConfig, String> {

    Optional<RerankConfig> findFirstByIsActiveTrue();

    List<RerankConfig> findAllByOrderByCreatedAtDesc();

    @Modifying
    @Query("UPDATE RerankConfig r SET r.isActive = false WHERE r.id != :activeId")
    void deactivateOthers(String activeId);

    @Modifying
    @Query("UPDATE RerankConfig r SET r.isActive = false")
    void deactivateAll();
}
