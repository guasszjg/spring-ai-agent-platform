package com.example.agentplatform.repository;

import com.example.agentplatform.model.AssistantConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AssistantConversationRepository extends JpaRepository<AssistantConversation, String> {

    List<AssistantConversation> findTop50ByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(String userId);

    Optional<AssistantConversation> findByIdAndUserIdAndDeletedAtIsNull(String id, String userId);

    /** 数据保留清理：消息由外键 ON DELETE CASCADE 一并删除。 */
    @Modifying
    @Query(value = "DELETE FROM assistant_conversations WHERE updated_at < :cutoff", nativeQuery = true)
    int deleteByUpdatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
