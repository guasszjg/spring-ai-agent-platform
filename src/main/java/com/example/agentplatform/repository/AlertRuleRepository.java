package com.example.agentplatform.repository;

import com.example.agentplatform.model.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRuleRepository extends JpaRepository<AlertRule, String> {
    List<AlertRule> findByEnabledTrue();
    List<AlertRule> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
    List<AlertRule> findAllByOrderByCreatedAtDesc();
}
