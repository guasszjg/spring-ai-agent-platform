package com.example.agentplatform.assistant;

import com.example.agentplatform.model.AssistantConversation;
import com.example.agentplatform.model.AssistantMessage;
import com.example.agentplatform.repository.AssistantConversationRepository;
import com.example.agentplatform.repository.AssistantMessageRepository;
import com.example.agentplatform.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 平台 AI 助手会话：会话只对创建者可见，别人的会话一律按"不存在"处理。
 */
@Service
public class AssistantConversationService {

    static final int TITLE_MAX = 30;

    private final AssistantConversationRepository conversationRepository;
    private final AssistantMessageRepository messageRepository;

    public AssistantConversationService(AssistantConversationRepository conversationRepository,
                                        AssistantMessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    /** 取当前用户自己的会话；conversationId 为空时新建，标题取首条消息。 */
    @Transactional
    public AssistantConversation openOrCreate(String conversationId, String firstMessage, CurrentActor actor) {
        if (conversationId != null && !conversationId.isBlank()) {
            return find(conversationId, actor)
                    .orElseThrow(() -> new IllegalArgumentException("会话不存在或已删除，请新建对话"));
        }
        AssistantConversation conversation = new AssistantConversation();
        conversation.setUserId(actor.getUserId());
        conversation.setTitle(titleFrom(firstMessage));
        return conversationRepository.save(conversation);
    }

    public Optional<AssistantConversation> find(String conversationId, CurrentActor actor) {
        if (conversationId == null || actor == null || actor.getUserId() == null) {
            return Optional.empty();
        }
        return conversationRepository.findByIdAndUserIdAndDeletedAtIsNull(conversationId, actor.getUserId());
    }

    @Transactional
    public AssistantMessage append(AssistantConversation conversation, AssistantMessage message) {
        message.setConversationId(conversation.getId());
        message.setUserId(conversation.getUserId());
        AssistantMessage saved = messageRepository.save(message);
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);
        return saved;
    }

    /** 作为模型上下文的最近历史（不含工具结果），按时间正序。 */
    public List<AssistantMessage> recentHistory(String conversationId, int limit) {
        List<AssistantMessage> all = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        return all.subList(Math.max(0, all.size() - limit), all.size());
    }

    public List<Map<String, Object>> list(CurrentActor actor) {
        if (actor == null || actor.getUserId() == null) {
            return List.of();
        }
        return conversationRepository.findTop50ByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(actor.getUserId()).stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("title", c.getTitle());
                    m.put("updatedAt", c.getUpdatedAt());
                    return m;
                })
                .toList();
    }

    public Optional<Map<String, Object>> detail(String conversationId, CurrentActor actor) {
        return find(conversationId, actor).map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("title", c.getTitle());
            m.put("updatedAt", c.getUpdatedAt());
            m.put("messages", messageRepository.findByConversationIdOrderByCreatedAtAsc(c.getId()));
            return m;
        });
    }

    /**
     * 软删除：对用户立即不可见并清空消息内容，但保留消息行上的 token 用量，
     * 避免删除会话后概览页的用量与成本变少；行数据由保留清理任务到期后物理删除。
     */
    @Transactional
    public boolean delete(String conversationId, CurrentActor actor) {
        Optional<AssistantConversation> conversation = find(conversationId, actor);
        conversation.ifPresent(c -> {
            messageRepository.clearContents(c.getId());
            c.setTitle(null);
            c.setDeletedAt(LocalDateTime.now());
            conversationRepository.save(c);
        });
        return conversation.isPresent();
    }

    static String titleFrom(String message) {
        if (message == null || message.isBlank()) {
            return "新对话";
        }
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() > TITLE_MAX ? compact.substring(0, TITLE_MAX) + "…" : compact;
    }
}
