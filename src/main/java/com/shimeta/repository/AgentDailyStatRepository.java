package com.shimeta.repository;

import com.shimeta.model.AgentDailyStat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AgentDailyStatRepository extends JpaRepository<AgentDailyStat, String> {
    Optional<AgentDailyStat> findByAgentIdAndStatDate(String agentId, LocalDate statDate);

    List<AgentDailyStat> findByStatDateBetween(LocalDate start, LocalDate end);

    List<AgentDailyStat> findByAgentId(String agentId);

    List<AgentDailyStat> findByAgentIdAndStatDateBetween(String agentId, LocalDate start, LocalDate end);
}
