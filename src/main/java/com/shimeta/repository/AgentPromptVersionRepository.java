package com.shimeta.repository;

import com.shimeta.model.AgentPromptVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface AgentPromptVersionRepository extends JpaRepository<AgentPromptVersion, String> {

    List<AgentPromptVersion> findByAgentIdOrderByVersionNoDesc(String agentId);

    Optional<AgentPromptVersion> findByIdAndAgentId(String id, String agentId);

    Optional<AgentPromptVersion> findFirstByAgentIdOrderByVersionNoDesc(String agentId);

    @Transactional
    @Modifying
    @Query("DELETE FROM AgentPromptVersion v WHERE v.agentId = :agentId")
    int deleteByAgentId(@Param("agentId") String agentId);
}
