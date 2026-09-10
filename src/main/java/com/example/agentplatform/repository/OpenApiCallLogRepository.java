package com.example.agentplatform.repository;

import com.example.agentplatform.model.OpenApiCallLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OpenApiCallLogRepository extends JpaRepository<OpenApiCallLog, String> {

    Page<OpenApiCallLog> findByOwnerIdOrderByTsDesc(String ownerId, Pageable pageable);

    Page<OpenApiCallLog> findAllByOrderByTsDesc(Pageable pageable);

    List<OpenApiCallLog> findByOwnerIdAndTsBetweenOrderByTsDesc(String ownerId, LocalDateTime start, LocalDateTime end);

    long countByOwnerIdAndTsAfter(String ownerId, LocalDateTime after);

    long countByOwnerIdAndHttpStatusGreaterThanEqualAndTsAfter(String ownerId, int minStatus, LocalDateTime after);
}
