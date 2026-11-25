package com.shimeta.repository;

import com.shimeta.model.AgentConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentConversationRepository extends JpaRepository<AgentConversation, String> {
    List<AgentConversation> findByAgentIdOrderByUpdatedAtDesc(String agentId);

    List<AgentConversation> findByAgentIdInOrderByUpdatedAtDesc(java.util.Collection<String> agentIds);

    /** 分享链接访客自己的会话 */
    List<AgentConversation> findTop50ByAgentIdAndAccountOrderByUpdatedAtDesc(String agentId, String account);

    /** 某个分享链接下所有访客的会话（account 以 share:{linkId}: 开头） */
    List<AgentConversation> findTop200ByAgentIdAndAccountStartingWithOrderByUpdatedAtDesc(String agentId, String accountPrefix);
}
