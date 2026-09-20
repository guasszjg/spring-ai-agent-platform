package com.example.agentplatform.repository;

import com.example.agentplatform.model.PlatformTool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlatformToolRepository extends JpaRepository<PlatformTool, String> {
    List<PlatformTool> findAllByOrderBySortOrderAsc();
    Optional<PlatformTool> findByCode(String code);
}
