package com.example.agentplatform.service;

import com.example.agentplatform.model.AgentConversation;
import com.example.agentplatform.model.AgentConversationMessage;
import com.example.agentplatform.model.ConversationDetail;
import com.example.agentplatform.model.PageResult;
import com.example.agentplatform.repository.AgentConversationMessageRepository;
import com.example.agentplatform.repository.AgentConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional
public class AgentConversationService {

    private final AgentConversationRepository conversationRepository;
    private final AgentConversationMessageRepository messageRepository;

    public AgentConversationService(AgentConversationRepository conversationRepository,
                                    AgentConversationMessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    public AgentConversation appendTurn(String agentId, String conversationId, String account,
                                        String userMessage, String assistantReply, String model,
                                        long latencyMs, int tokensUsed) {
        AgentConversation conversation = resolveConversation(agentId, conversationId, account, userMessage);
        LocalDateTime now = LocalDateTime.now();

        AgentConversationMessage user = new AgentConversationMessage();
        user.setConversationId(conversation.getId());
        user.setRole("user");
        user.setContent(userMessage);
        user.setCreatedAt(now);
        messageRepository.save(user);

        AgentConversationMessage assistant = new AgentConversationMessage();
        assistant.setConversationId(conversation.getId());
        assistant.setRole("assistant");
        assistant.setContent(assistantReply);
        assistant.setModel(model);
        assistant.setLatencyMs(latencyMs);
        assistant.setTokensUsed(tokensUsed);
        assistant.setCreatedAt(now.plusNanos(1_000_000));
        messageRepository.save(assistant);

        conversation.setMessageCount(conversation.getMessageCount() + 2);
        conversation.setLastModel(model);
        conversation.setTotalTokens(conversation.getTotalTokens() + Math.max(0, tokensUsed));
        conversation.setUpdatedAt(now);
        if (account != null && !account.isBlank()) {
            conversation.setAccount(account.trim());
        }
        return conversationRepository.save(conversation);
    }

    @Transactional(readOnly = true)
    public PageResult<AgentConversation> listLogs(String agentId, String range, String keyword,
                                                  String sort, String order, int page, int size) {
        LocalDate[] bounds = TimeRange.resolve(range);
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        boolean asc = "asc".equalsIgnoreCase(order);
        Comparator<AgentConversation> comparator = "updatedAt".equalsIgnoreCase(sort)
                ? Comparator.comparing(AgentConversation::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                : Comparator.comparing(AgentConversation::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
        if (!asc) {
            comparator = comparator.reversed();
        }

        List<AgentConversation> filtered = conversationRepository.findByAgentIdOrderByUpdatedAtDesc(agentId).stream()
                .filter(item -> inRange(item.getCreatedAt(), bounds))
                .filter(item -> kw.isEmpty()
                        || contains(item.getTitle(), kw)
                        || contains(item.getAccount(), kw))
                .sorted(comparator)
                .collect(Collectors.toList());

        int total = filtered.size();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        int from = Math.min((safePage - 1) * safeSize, total);
        int to = Math.min(from + safeSize, total);
        return new PageResult<>(filtered.subList(from, to), total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public Optional<ConversationDetail> getDetail(String agentId, String conversationId) {
        return conversationRepository.findById(conversationId)
                .filter(item -> agentId.equals(item.getAgentId()))
                .map(item -> new ConversationDetail(
                        item,
                        messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
                ));
    }

    @Transactional(readOnly = true)
    public List<AgentConversation> listInRange(String agentId, String range) {
        LocalDate[] bounds = TimeRange.resolve(range);
        return conversationRepository.findByAgentIdOrderByUpdatedAtDesc(agentId).stream()
                .filter(item -> inRange(item.getCreatedAt(), bounds))
                .collect(Collectors.toList());
    }

    private AgentConversation resolveConversation(String agentId, String conversationId, String account, String userMessage) {
        if (conversationId != null && !conversationId.isBlank()) {
            Optional<AgentConversation> existing = conversationRepository.findById(conversationId.trim());
            if (existing.isPresent() && agentId.equals(existing.get().getAgentId())) {
                return existing.get();
            }
        }
        AgentConversation created = new AgentConversation();
        created.setAgentId(agentId);
        created.setTitle(truncateTitle(userMessage));
        created.setAccount(account != null && !account.isBlank() ? account.trim() : "debug");
        created.setMessageCount(0);
        return conversationRepository.save(created);
    }

    private static boolean inRange(LocalDateTime time, LocalDate[] bounds) {
        if (time == null) {
            return false;
        }
        LocalDate day = time.toLocalDate();
        if (bounds[0] != null && day.isBefore(bounds[0])) {
            return false;
        }
        return bounds[1] == null || !day.isAfter(bounds[1]);
    }

    private static boolean contains(String value, String kw) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(kw);
    }

    private static String truncateTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未命名会话";
        }
        String title = raw.trim().replaceAll("\\s+", " ");
        return title.length() <= 80 ? title : title.substring(0, 80);
    }
}
