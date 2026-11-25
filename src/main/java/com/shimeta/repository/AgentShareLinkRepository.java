package com.shimeta.repository;

import com.shimeta.model.AgentShareLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AgentShareLinkRepository extends JpaRepository<AgentShareLink, String> {

    Optional<AgentShareLink> findByCode(String code);

    List<AgentShareLink> findByAgentIdOrderByCreatedAtDesc(String agentId);

    List<AgentShareLink> findByAgentIdIn(Collection<String> agentIds);

    boolean existsByCode(String code);

    /** 预占一次对话：未达到对话次数上限时加 1，返回 0 表示已用完 */
    @Transactional
    @Modifying
    @Query("""
            UPDATE AgentShareLink l SET l.messageCount = l.messageCount + 1, l.lastUsedAt = :now
            WHERE l.id = :id AND (l.maxMessages IS NULL OR l.messageCount < l.maxMessages)
            """)
    int reserveMessage(@Param("id") String id, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE AgentShareLink l SET l.tokenCount = l.tokenCount + :tokens WHERE l.id = :id")
    int addTokens(@Param("id") String id, @Param("tokens") long tokens);

    /** 新访客占一个名额：未达到访客数上限时加 1，返回 0 表示已满 */
    @Transactional
    @Modifying
    @Query("""
            UPDATE AgentShareLink l SET l.visitorCount = l.visitorCount + 1
            WHERE l.id = :id AND (l.maxVisitors IS NULL OR l.visitorCount < l.maxVisitors)
            """)
    int reserveVisitor(@Param("id") String id);
}
