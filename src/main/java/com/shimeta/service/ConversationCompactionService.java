package com.shimeta.service;

import com.shimeta.model.Agent;
import com.shimeta.model.AgentConversation;
import com.shimeta.model.AgentConversationMessage;
import com.shimeta.model.AssistantConversation;
import com.shimeta.model.AssistantMessage;
import com.shimeta.model.ChatGeneration;
import com.shimeta.model.LlmProtocolType;
import com.shimeta.model.LlmProvider;
import com.shimeta.repository.AgentConversationMessageRepository;
import com.shimeta.repository.AgentConversationRepository;
import com.shimeta.repository.AssistantConversationRepository;
import com.shimeta.repository.AssistantMessageRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一的会话滚动记忆压缩服务（供平台 AI 助手与开启了「记忆压缩」的智能体共用）。
 *
 * <p>核心原则：
 * <ol>
 *   <li>短对话零开销：未超出保留滑窗时不触发压缩；</li>
 *   <li>批量滚动保护缓存：每累积挤出 {@value #MIN_BATCH_MESSAGES} 条旧消息才增量合并一次，避免每轮改写 system 前缀；</li>
 *   <li>异步执行不拖慢首字：常规对话结束后后台异步浓缩并落库，下轮自动生效；</li>
 *   <li>异常平滑降级：模型通道不可用或超时时退回确定性规则摘要，绝不影响正常对话。</li>
 * </ol>
 */
@Service
public class ConversationCompactionService {

    private static final Logger log = LoggerFactory.getLogger(ConversationCompactionService.class);

    /** AI 助手自动压缩时原文保留的最近消息条数（4 轮 = 8 条） */
    public static final int ASSISTANT_KEEP_RECENT = 8;
    /** 手动压缩 AI 助手时原文保留的最近消息条数（2 轮 = 4 条） */
    public static final int ASSISTANT_MANUAL_KEEP_RECENT = 4;
    /** 每次自动滚动压缩至少累积的新溢出消息条数（2 轮 = 4 条），避免每轮重写破坏 Prompt Cache */
    public static final int MIN_BATCH_MESSAGES = 4;
    static final int MAX_SUMMARY_CHARS = 900;
    private static final int MAX_ITEM_CHARS = 600;
    private static final int MAX_TRANSCRIPT_CHARS = 6000;
    private static final int COMPACT_TIMEOUT_MS = 20_000;

    public record CompactionItem(String role, String content) {}

    public record CompactionResult(boolean compacted, String memorySummary, int summaryMsgCount, String message) {}

    private final LlmGatewayService gatewayService;
    private final OpenAiCompatibleClient openAiClient;
    private final AssistantConversationRepository assistantConversationRepository;
    private final AssistantMessageRepository assistantMessageRepository;
    private final AgentConversationRepository agentConversationRepository;
    private final AgentConversationMessageRepository agentMessageRepository;
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public ConversationCompactionService(LlmGatewayService gatewayService,
                                         OpenAiCompatibleClient openAiClient,
                                         AssistantConversationRepository assistantConversationRepository,
                                         AssistantMessageRepository assistantMessageRepository,
                                         AgentConversationRepository agentConversationRepository,
                                         AgentConversationMessageRepository agentMessageRepository) {
        this.gatewayService = gatewayService;
        this.openAiClient = openAiClient;
        this.assistantConversationRepository = assistantConversationRepository;
        this.assistantMessageRepository = assistantMessageRepository;
        this.agentConversationRepository = agentConversationRepository;
        this.agentMessageRepository = agentMessageRepository;
        AtomicInteger seq = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "memory-compaction-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** AI 助手每轮结束后异步检查：仅当超出滑窗的新消息累积达到阈值时才后台滚动摘要 */
    public void compactAssistantAsync(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        String lockKey = "asc:" + conversationId;
        if (inFlight.putIfAbsent(lockKey, Boolean.TRUE) != null) {
            return;
        }
        executor.execute(() -> {
            try {
                compactAssistantInternal(conversationId, ASSISTANT_KEEP_RECENT, MIN_BATCH_MESSAGES);
            } catch (Exception e) {
                log.warn("Async assistant memory compaction failed for [{}]: {}", conversationId, e.getMessage());
            } finally {
                inFlight.remove(lockKey);
            }
        });
    }

    /** AI 助手手动触发压缩（立即执行并返回最新摘要） */
    public CompactionResult compactAssistantNow(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return new CompactionResult(false, null, 0, "会话不存在");
        }
        String lockKey = "asc:" + conversationId;
        if (inFlight.putIfAbsent(lockKey, Boolean.TRUE) != null) {
            AssistantConversation existing = assistantConversationRepository.findById(conversationId).orElse(null);
            return new CompactionResult(false,
                    existing != null ? existing.getMemorySummary() : null,
                    existing != null ? existing.getSummaryMsgCount() : 0,
                    "正在压缩中，请稍候");
        }
        try {
            return compactAssistantInternal(conversationId, ASSISTANT_MANUAL_KEEP_RECENT, 2);
        } finally {
            inFlight.remove(lockKey);
        }
    }

    /** 智能体每轮结束后异步检查：仅开启「记忆压缩」且溢出消息累积达阈值时触发 */
    public void compactAgentAsync(Agent agent, String conversationId) {
        if (agent == null || !agent.isMemoryCompressionEnabled() || conversationId == null || conversationId.isBlank()) {
            return;
        }
        String lockKey = "conv:" + conversationId;
        if (inFlight.putIfAbsent(lockKey, Boolean.TRUE) != null) {
            return;
        }
        int keepRecent = Math.max(2, agent.resolveMemoryTurns() * 2);
        String preferredProviderId = agent.getPrimaryProviderId();
        String preferredModel = agent.getModelName();
        executor.execute(() -> {
            try {
                compactAgentInternal(conversationId, keepRecent, preferredProviderId, preferredModel);
            } catch (Exception e) {
                log.warn("Async agent memory compaction failed for [{}]: {}", conversationId, e.getMessage());
            } finally {
                inFlight.remove(lockKey);
            }
        });
    }

    CompactionResult compactAssistantInternal(String conversationId, int keepRecent, int minBatch) {
        Optional<AssistantConversation> convOpt = assistantConversationRepository.findById(conversationId);
        if (convOpt.isEmpty() || convOpt.get().getDeletedAt() != null) {
            return new CompactionResult(false, null, 0, "会话不存在");
        }
        AssistantConversation conv = convOpt.get();
        List<AssistantMessage> all = assistantMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        int targetCount = Math.max(0, all.size() - keepRecent);
        int alreadyCount = Math.min(conv.getSummaryMsgCount(), all.size());
        if (targetCount - alreadyCount < minBatch) {
            return new CompactionResult(false, conv.getMemorySummary(), alreadyCount,
                    all.size() <= keepRecent ? "当前对话较短，暂无需压缩" : "已是最新记忆摘要");
        }

        List<CompactionItem> slice = new ArrayList<>();
        for (int i = alreadyCount; i < targetCount; i++) {
            AssistantMessage m = all.get(i);
            if (m == null || Boolean.TRUE.equals(m.getDegraded()) || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            slice.add(new CompactionItem(m.getRole(), m.getContent()));
        }
        if (slice.isEmpty()) {
            conv.setSummaryMsgCount(targetCount);
            assistantConversationRepository.save(conv);
            return new CompactionResult(false, conv.getMemorySummary(), targetCount, "已是最新记忆摘要");
        }

        var routeOpt = gatewayService.resolveAssistantRoute();
        LlmGatewayService.ResolvedRoute route = routeOpt.map(LlmGatewayService.AssistantRoute::route).orElse(null);
        String preferredModel = routeOpt.map(LlmGatewayService.AssistantRoute::model).orElse(null);
        String nextSummary = summarize(conv.getMemorySummary(), slice, "平台 AI 助手管理与排查对话", route, preferredModel);

        conv.setMemorySummary(nextSummary);
        conv.setSummaryMsgCount(targetCount);
        assistantConversationRepository.save(conv);
        log.info("Compacted assistant conversation [{}]: {} -> {} summarized messages ({} chars)",
                conversationId, alreadyCount, targetCount, nextSummary.length());
        return new CompactionResult(true, nextSummary, targetCount, "已压缩早期 " + targetCount + " 条消息");
    }

    CompactionResult compactAgentInternal(String conversationId, int keepRecent,
                                          String preferredProviderId, String preferredModel) {
        Optional<AgentConversation> convOpt = agentConversationRepository.findById(conversationId);
        if (convOpt.isEmpty()) {
            return new CompactionResult(false, null, 0, "会话不存在");
        }
        AgentConversation conv = convOpt.get();
        List<AgentConversationMessage> all = agentMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        int targetCount = Math.max(0, all.size() - keepRecent);
        int alreadyCount = Math.min(conv.getSummaryMsgCount(), all.size());
        if (targetCount - alreadyCount < MIN_BATCH_MESSAGES) {
            return new CompactionResult(false, conv.getMemorySummary(), alreadyCount, "暂未达压缩阈值");
        }

        List<CompactionItem> slice = new ArrayList<>();
        for (int i = alreadyCount; i < targetCount; i++) {
            AgentConversationMessage m = all.get(i);
            if (m == null || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            slice.add(new CompactionItem(m.getRole(), m.getContent()));
        }
        if (slice.isEmpty()) {
            conv.setSummaryMsgCount(targetCount);
            agentConversationRepository.save(conv);
            return new CompactionResult(false, conv.getMemorySummary(), targetCount, "无有效新增消息");
        }

        // 优先走助手/默认路由（通常更稳定且便宜），不占用主流程资源
        var assistantRoute = gatewayService.resolveAssistantRoute();
        LlmGatewayService.ResolvedRoute route = assistantRoute.map(LlmGatewayService.AssistantRoute::route).orElse(null);
        String model = assistantRoute.map(LlmGatewayService.AssistantRoute::model).orElse(null);
        if (route == null) {
            route = gatewayService.resolveRoute(preferredModel).orElse(null);
            model = preferredModel;
        }
        String nextSummary = summarize(conv.getMemorySummary(), slice, "智能体多轮用户对话", route, model);

        conv.setMemorySummary(nextSummary);
        conv.setSummaryMsgCount(targetCount);
        agentConversationRepository.save(conv);
        log.info("Compacted agent conversation [{}]: {} -> {} summarized messages ({} chars)",
                conversationId, alreadyCount, targetCount, nextSummary.length());
        return new CompactionResult(true, nextSummary, targetCount, "已压缩早期 " + targetCount + " 条消息");
    }

    /** 调用大模型做增量滚动摘要；模型不可用或异常时平滑退回规则摘要 */
    String summarize(String existingSummary, List<CompactionItem> newItems, String domainHint,
                     LlmGatewayService.ResolvedRoute route, String preferredModel) {
        String transcript = formatTranscript(newItems);
        if (route != null && route.primary() != null && route.primary().getProtocol() != LlmProtocolType.CUSTOM_HTTP) {
            LlmProvider provider = route.primary();
            String model = (preferredModel != null && !preferredModel.isBlank()) ? preferredModel : provider.getDefaultModel();
            if (model != null && !model.isBlank()) {
                try {
                    String sys = "你是严谨的对话记忆压缩助手（场景：" + domainHint + "）。"
                            + "请将「已有摘要」与「新增早期对话」合并浓缩为一份简洁的中文结构化要点摘要（总字数控制在 400 字以内）：\n"
                            + "1. 必须完整保留关键实体名称与精确标识（如智能体名/ID、知识库名/ID、型号、订单号、错误码、数值）；\n"
                            + "2. 提炼用户核心诉求、已确认的结论、已执行/待办事项；\n"
                            + "3. 去掉寒暄、重复客套和冗余表格细节；\n"
                            + "4. 只输出摘要正文，不要输出任何前言或解释。";
                    StringBuilder user = new StringBuilder();
                    if (existingSummary != null && !existingSummary.isBlank()) {
                        user.append("【已有记忆摘要】\n").append(existingSummary.trim()).append("\n\n");
                    }
                    user.append("【新增待压缩对话】\n").append(transcript);

                    ChatGeneration gen = new ChatGeneration();
                    gen.setTemperature(0.2);
                    gen.setMaxTokens(600);
                    gen.setThinking(Boolean.FALSE);
                    gen.setProviderProtocol(provider.getProtocol());

                    List<Map<String, Object>> msgs = List.of(
                            Map.of("role", "system", "content", sys),
                            Map.of("role", "user", "content", user.toString())
                    );
                    OpenAiCompatibleClient.ChatResult res = openAiClient.chatWithTools(
                            provider.getBaseUrl(),
                            route.primaryKey(),
                            model,
                            LlmGatewayService.parseCustomHeaders(provider.getCustomConfig()),
                            msgs,
                            null,
                            null,
                            gen,
                            COMPACT_TIMEOUT_MS
                    );
                    if (res != null && res.content() != null && !res.content().isBlank()) {
                        return clamp(res.content().trim(), MAX_SUMMARY_CHARS);
                    }
                } catch (Exception e) {
                    log.debug("LLM memory compaction unavailable, using rule fallback: {}", e.getMessage());
                }
            }
        }
        return buildRuleFallbackSummary(existingSummary, newItems);
    }

    static String buildRuleFallbackSummary(String existingSummary, List<CompactionItem> newItems) {
        StringBuilder sb = new StringBuilder();
        if (existingSummary != null && !existingSummary.isBlank()) {
            sb.append(existingSummary.trim()).append('\n');
        }
        for (CompactionItem item : newItems) {
            String roleLabel = "user".equalsIgnoreCase(item.role()) ? "用户" : "助手";
            String clean = item.content().replaceAll("\\s+", " ").trim();
            if (clean.length() > 140) {
                clean = clean.substring(0, 90) + "…" + clean.substring(clean.length() - 48);
            }
            sb.append("- ").append(roleLabel).append("：").append(clean).append('\n');
        }
        return clamp(sb.toString().trim(), MAX_SUMMARY_CHARS);
    }

    private static String formatTranscript(List<CompactionItem> items) {
        StringBuilder sb = new StringBuilder();
        for (CompactionItem item : items) {
            String roleLabel = "user".equalsIgnoreCase(item.role()) ? "用户" : "助手";
            String text = item.content().trim();
            if (text.length() > MAX_ITEM_CHARS) {
                text = text.substring(0, MAX_ITEM_CHARS - 160) + "…（略）…" + text.substring(text.length() - 150);
            }
            String line = roleLabel + "：" + text + "\n";
            if (sb.length() + line.length() > MAX_TRANSCRIPT_CHARS) {
                break;
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private static String clamp(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars - 1) + "…";
    }
}
