package com.example.agentplatform.repository;

import com.example.agentplatform.model.AgentDailyStat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AgentDailyStatRepository extends JpaRepository<AgentDailyStat, String> {
    Optional<AgentDailyStat> findByAgentIdAndStatDate(String agentId, LocalDate statDate);

    List<AgentDailyStat> findByStatDateBetween(LocalDate start, LocalDate end);
}
