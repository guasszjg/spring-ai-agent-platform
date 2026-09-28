package com.example.agentplatform.repository;

import com.example.agentplatform.model.AssistantAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssistantActionRepository extends JpaRepository<AssistantAction, String> {

    List<AssistantAction> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    Optional<AssistantAction> findByIdAndUserId(String id, String userId);

    /**
     * 条件更新抢占执行权：只有属于该用户、仍为 PENDING 且未过期的操作才能进入 EXECUTING，
     * 返回 1 表示抢占成功。并发重复点击时只有一次能成功。
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE assistant_actions SET status = 'EXECUTING'
            WHERE id = :id AND user_id = :userId AND status = 'PENDING' AND expires_at > :now
            """, nativeQuery = true)
    int claim(@Param("id") String id, @Param("userId") String userId, @Param("now") LocalDateTime now);

    /** 只有 PENDING 才能取消 */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE assistant_actions SET status = 'CANCELLED' WHERE id = :id AND user_id = :userId AND status = 'PENDING'",
            nativeQuery = true)
    int cancel(@Param("id") String id, @Param("userId") String userId);

    /** 助手回复落库后，把本轮生成的操作关联到这条回复 */
    @Transactional
    @Modifying
    @Query(value = "UPDATE assistant_actions SET message_id = :messageId WHERE id IN (:ids)", nativeQuery = true)
    int attachMessage(@Param("messageId") String messageId, @Param("ids") Collection<String> ids);

    /** 删除会话时：未处理的操作作废，并清空参数与展示内容（其中可能包含用户输入的提示词等内容） */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE assistant_actions
            SET status = CASE WHEN status = 'PENDING' THEN 'CANCELLED' ELSE status END,
                payload = '{}', preview = NULL
            WHERE conversation_id = :conversationId
            """, nativeQuery = true)
    int clearForConversation(@Param("conversationId") String conversationId);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM assistant_actions WHERE created_at < :cutoff", nativeQuery = true)
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
