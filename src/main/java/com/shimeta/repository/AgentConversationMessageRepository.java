package com.shimeta.repository;

import com.shimeta.model.AgentConversationMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface AgentConversationMessageRepository extends JpaRepository<AgentConversationMessage, String> {
    List<AgentConversationMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    List<AgentConversationMessage> findByConversationIdOrderByCreatedAtDesc(String conversationId, Pageable pageable);

    /** 每行：[model, token 合计] */
    @Query("select m.model, sum(m.tokensUsed) from AgentConversationMessage m"
            + " where m.role = 'assistant' and m.tokensUsed > 0 and m.createdAt >= :start and m.createdAt < :end"
            + " group by m.model")
    List<Object[]> sumTokensByModel(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 每行：[model, token 合计]，只统计指定智能体 */
    @Query("select m.model, sum(m.tokensUsed) from AgentConversationMessage m, AgentConversation c"
            + " where c.id = m.conversationId and c.agentId in :agentIds"
            + " and m.role = 'assistant' and m.tokensUsed > 0 and m.createdAt >= :start and m.createdAt < :end"
            + " group by m.model")
    List<Object[]> sumTokensByModelForAgents(@Param("agentIds") Collection<String> agentIds,
                                             @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    void deleteByConversationId(String conversationId);
}
