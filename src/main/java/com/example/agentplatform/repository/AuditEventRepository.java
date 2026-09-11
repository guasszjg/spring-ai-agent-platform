package com.example.agentplatform.repository;

import com.example.agentplatform.model.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {
    Page<AuditEvent> findByOwnerIdOrderByOccurredAtDesc(String ownerId, Pageable pageable);
    List<AuditEvent> findByOwnerIdAndOccurredAtBetweenOrderByOccurredAtDesc(String ownerId, LocalDateTime start, LocalDateTime end);
    Page<AuditEvent> findAllByOrderByOccurredAtDesc(Pageable pageable);
    Page<AuditEvent> findByRiskLevelOrderByOccurredAtDesc(String riskLevel, Pageable pageable);
    Page<AuditEvent> findByResultOrderByOccurredAtDesc(String result, Pageable pageable);
    Page<AuditEvent> findByOwnerIdAndRiskLevelOrderByOccurredAtDesc(String ownerId, String riskLevel, Pageable pageable);
    Page<AuditEvent> findByOwnerIdAndResultOrderByOccurredAtDesc(String ownerId, String result, Pageable pageable);
    List<AuditEvent> findTop10ByRiskLevelOrderByOccurredAtDesc(String riskLevel);
    long countByOccurredAtAfterAndResult(LocalDateTime after, String result);
    long countByOwnerIdAndOccurredAtAfterAndResult(String ownerId, LocalDateTime after, String result);
    long countByOccurredAtAfterAndRiskLevel(LocalDateTime after, String riskLevel);
    long countByOwnerIdAndOccurredAtAfterAndRiskLevel(String ownerId, LocalDateTime after, String riskLevel);
    long deleteByOccurredAtBefore(LocalDateTime before);
    List<AuditEvent> findByOwnerIdOrderByOccurredAtDesc(String ownerId);
    List<AuditEvent> findAllByOrderByOccurredAtDesc();
}
