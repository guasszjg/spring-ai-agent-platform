package com.shimeta.repository;

import com.shimeta.model.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRuleRepository extends JpaRepository<AlertRule, String> {
    List<AlertRule> findByEnabledTrue();
    List<AlertRule> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
    List<AlertRule> findAllByOrderByCreatedAtDesc();
}
