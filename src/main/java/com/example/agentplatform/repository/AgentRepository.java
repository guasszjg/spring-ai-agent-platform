package com.example.agentplatform.repository;

import com.example.agentplatform.model.Agent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, String> {
    Optional<Agent> findByCode(String code);
}
