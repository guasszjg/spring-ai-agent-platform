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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        return listLogs(null, null, agentId, range, keyword, sort, order, page, size);
    }

    @Transactional(readOnly = true)
    public PageResult<AgentConversation> listLogs(com.example.agentplatform.model.Agent agent,
                                                  com.example.agentplatform.security.CurrentActor actor,
                                                  String range, String keyword,
                                                  String sort, String order, int page, int size) {
        String agentId = agent != null ? agent.getId() : null;
        return listLogs(agent, actor, agentId, range, keyword, sort, order, page, size);
    }

    private PageResult<AgentConversation> listLogs(com.example.agentplatform.model.Agent agent,
                                                   com.example.agentplatform.security.CurrentActor actor,
                                                   String agentId, String range, String keyword,
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

        boolean isOwnerOrAdmin = actor == null || actor.isSuperAdmin()
                || (agent != null && actor.getUserId() != null && actor.getUserId().equals(agent.getOwnerId()));

        List<AgentConversation> filtered = conversationRepository.findByAgentIdOrderByUpdatedAtDesc(agentId).stream()
                .filter(item -> inRange(item.getCreatedAt(), bounds))
                .filter(item -> {
                    if (isOwnerOrAdmin) {
                        return true;
                    }
                    return actor.getUsername() != null && actor.getUsername().equalsIgnoreCase(item.getAccount());
                })
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
    public Optional<ConversationDetail> getDetail(com.example.agentplatform.model.Agent agent,
                                                  com.example.agentplatform.security.CurrentActor actor,
                                                  String conversationId) {
        if (agent == null) {
            return Optional.empty();
        }
        Optional<AgentConversation> opt = conversationRepository.findById(conversationId)
                .filter(item -> agent.getId().equals(item.getAgentId()));
        if (opt.isEmpty()) {
            return Optional.empty();
        }

        boolean isOwnerOrAdmin = actor == null || actor.isSuperAdmin()
                || (actor.getUserId() != null && actor.getUserId().equals(agent.getOwnerId()));
        if (!isOwnerOrAdmin) {
            String account = opt.get().getAccount();
            if (account == null || !account.equalsIgnoreCase(actor.getUsername())) {
                throw new IllegalStateException("权限不足：无法查看其他用户的会话记录");
            }
        }

        return Optional.of(new ConversationDetail(
                opt.get(),
                messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
        ));
    }

    @Transactional(readOnly = true)
    public Map<String, String> latestModelByAgent() {
        return latestModelByAgent(null);
    }

    @Transactional(readOnly = true)
    public Map<String, String> latestModelByAgent(java.util.Set<String> allowedAgentIds) {
        Map<String, String> latest = new LinkedHashMap<>();
        conversationRepository.findAll().stream()
                .filter(item -> allowedAgentIds == null || allowedAgentIds.contains(item.getAgentId()))
                .sorted(Comparator.comparing(AgentConversation::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .forEach(item -> {
                    if (item.getAgentId() == null || item.getAgentId().isBlank()) {
                        return;
                    }
                    if (item.getLastModel() == null || item.getLastModel().isBlank()) {
                        return;
                    }
                    latest.putIfAbsent(item.getAgentId(), item.getLastModel().trim());
                });
        return latest;
    }

    @Transactional(readOnly = true)
    public Map<String, Long> tokenUsageByModel(LocalDate start, LocalDate end) {
        return tokenUsageByModel(null, null, start, end);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> tokenUsageByModel(String agentId, LocalDate start, LocalDate end) {
        return tokenUsageByModel(agentId, null, start, end);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> tokenUsageByModel(String agentId, java.util.Set<String> allowedAgentIds, LocalDate start, LocalDate end) {
        LocalDateTime from = (start == null ? LocalDate.of(2020, 1, 1) : start).atStartOfDay();
        LocalDateTime to = (end == null ? LocalDate.now() : end).plusDays(1).atStartOfDay();
        Map<String, Long> usage = new LinkedHashMap<>();
        List<AgentConversationMessage> messages = messageRepository
                .findByRoleAndCreatedAtGreaterThanEqualAndCreatedAtLessThan("assistant", from, to);

        final java.util.Set<String> finalTargetAgentIds = (agentId != null && !agentId.isBlank())
                ? java.util.Set.of(agentId)
                : allowedAgentIds;

        if (finalTargetAgentIds != null) {
            java.util.Set<String> validConvIds = conversationRepository.findAll().stream()
                    .filter(c -> finalTargetAgentIds.contains(c.getAgentId()))
                    .map(AgentConversation::getId)
                    .collect(Collectors.toSet());
            messages = messages.stream().filter(item -> validConvIds.contains(item.getConversationId())).collect(Collectors.toList());
        }

        for (AgentConversationMessage message : messages) {
            if (message.getTokensUsed() == null || message.getTokensUsed() <= 0) {
                continue;
            }
            String model = message.getModel() == null || message.getModel().isBlank()
                    ? "未指定"
                    : message.getModel().trim();
            usage.merge(model, message.getTokensUsed().longValue(), Long::sum);
        }
        return usage;
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
