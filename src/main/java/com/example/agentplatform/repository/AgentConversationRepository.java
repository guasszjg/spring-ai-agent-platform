package com.example.agentplatform.repository;

import com.example.agentplatform.model.AgentConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentConversationRepository extends JpaRepository<AgentConversation, String> {
    List<AgentConversation> findByAgentIdOrderByUpdatedAtDesc(String agentId);
}
