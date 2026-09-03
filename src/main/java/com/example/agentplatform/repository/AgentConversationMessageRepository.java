package com.example.agentplatform.repository;

import com.example.agentplatform.model.AgentConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentConversationMessageRepository extends JpaRepository<AgentConversationMessage, String> {
    List<AgentConversationMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    List<AgentConversationMessage> findByRoleAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            String role, LocalDateTime start, LocalDateTime end);
}
