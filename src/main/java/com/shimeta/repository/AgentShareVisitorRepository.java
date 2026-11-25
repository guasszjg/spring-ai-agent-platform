package com.shimeta.repository;

import com.shimeta.model.AgentShareVisitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentShareVisitorRepository extends JpaRepository<AgentShareVisitor, String> {

    Optional<AgentShareVisitor> findByTokenHash(String tokenHash);

    List<AgentShareVisitor> findByLinkIdOrderByLastSeenAtDesc(String linkId);

    @Transactional
    @Modifying
    @Query("""
            UPDATE AgentShareVisitor v SET v.messageCount = v.messageCount + 1, v.lastSeenAt = :now, v.lastIp = :ip
            WHERE v.id = :id
            """)
    int recordMessage(@Param("id") String id, @Param("now") LocalDateTime now, @Param("ip") String ip);

    @Transactional
    @Modifying
    @Query("UPDATE AgentShareVisitor v SET v.tokenCount = v.tokenCount + :tokens WHERE v.id = :id")
    int addTokens(@Param("id") String id, @Param("tokens") long tokens);

    /** 重置链接时作废所有访客令牌 */
    @Transactional
    @Modifying
    @Query("UPDATE AgentShareVisitor v SET v.tokenHash = NULL, v.tokenExpiresAt = NULL WHERE v.linkId = :linkId")
    int revokeTokens(@Param("linkId") String linkId);

    @Transactional
    @Modifying
    @Query("DELETE FROM AgentShareVisitor v WHERE v.linkId = :linkId")
    int deleteByLinkId(@Param("linkId") String linkId);
}
