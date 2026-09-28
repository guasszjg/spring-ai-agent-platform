package com.example.agentplatform.repository;

import com.example.agentplatform.model.AssistantMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, String> {

    List<AssistantMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    long countByConversationId(String conversationId);

    /** 删除会话时清空消息内容，保留 token 用量 */
    @Modifying
    @Query(value = "UPDATE assistant_messages SET content = NULL, tool_calls = NULL WHERE conversation_id = :conversationId",
            nativeQuery = true)
    int clearContents(@Param("conversationId") String conversationId);

    /** 全站助手 token 用量，按日期与模型聚合：[日期, 模型, prompt, completion]。 */
    @Query(value = """
            SELECT CAST(created_at AS DATE), model,
                   COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(completion_tokens), 0)
            FROM assistant_messages
            WHERE role = 'assistant' AND created_at >= :start AND created_at < :end
            GROUP BY CAST(created_at AS DATE), model
            """, nativeQuery = true)
    List<Object[]> sumTokensByDayAndModel(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 指定用户的助手 token 用量，按日期与模型聚合：[日期, 模型, prompt, completion]。 */
    @Query(value = """
            SELECT CAST(created_at AS DATE), model,
                   COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(completion_tokens), 0)
            FROM assistant_messages
            WHERE role = 'assistant' AND user_id = :userId AND created_at >= :start AND created_at < :end
            GROUP BY CAST(created_at AS DATE), model
            """, nativeQuery = true)
    List<Object[]> sumTokensByDayAndModelForUser(@Param("userId") String userId,
                                                @Param("start") LocalDateTime start,
                                                @Param("end") LocalDateTime end);
}
