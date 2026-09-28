package com.example.agentplatform.assistant;

import com.example.agentplatform.model.AssistantAction;
import com.example.agentplatform.model.AssistantConversation;
import com.example.agentplatform.model.AssistantMessage;
import com.example.agentplatform.model.ChatGeneration;
import com.example.agentplatform.model.GuardrailPolicy;
import com.example.agentplatform.model.LlmProtocolType;
import com.example.agentplatform.model.LlmProvider;
import com.example.agentplatform.rag.RetrievedChunk;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.security.guardrail.ContentGuardService;
import com.example.agentplatform.security.guardrail.InputGuardResult;
import com.example.agentplatform.security.guardrail.StreamingSlidingWindowGuard;
import com.example.agentplatform.service.AiChatService;
import com.example.agentplatform.service.AuditRecorder;
import com.example.agentplatform.service.CustomHttpLlmClient;
import com.example.agentplatform.service.GuardrailPolicyService;
import com.example.agentplatform.service.LlmGatewayService;
import com.example.agentplatform.service.OpenAiCompatibleClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 平台 AI 助手的流式对话（P1）。
 *
 * <p>SSE 事件：
 * <ul>
 *   <li>{@code start}：{conversationId}，会话已建立；</li>
 *   <li>{@code message}：{delta}，回复的增量文本；</li>
 *   <li>{@code tool}：{id, name, label, status: running|done|failed}，工具调用进度；</li>
 *   <li>{@code action}：操作卡片数据，写工具生成了待确认操作（见 {@link AssistantActionService#card}）；</li>
 *   <li>{@code guardrail}：{message}，输出命中护栏，流式输出已中断；</li>
 *   <li>{@code done}：{conversationId, messageId, content, model, latencyMs, promptTokens, completionTokens, tools, mode, degraded, notice, actionIds}；</li>
 *   <li>{@code error}：{message}，请求无法处理（输入被拦截、会话不存在等）。</li>
 * </ul>
 *
 * <p>工具阶段不流式、最终回复流式：每一轮都以流式请求模型，内容增量直接推给前端；模型发起工具调用时执行只读工具，
 * 结果回传后进入下一轮，最多 {@link #MAX_ROUNDS} 轮，最后一轮不再提供工具以迫使模型作答。
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    static final int MAX_ROUNDS = 5;
    static final int HISTORY_LIMIT = 12;
    static final int HISTORY_MESSAGE_MAX_CHARS = 2000;
    static final long SSE_TIMEOUT_MS = 300_000L;

    static final String NO_CHANNEL_REPLY = "当前还没有可用的大模型通道。请先到「模型网关 → 大语言模型」中添加并启用一个模型通道，然后再来和我对话。";
    static final String CHANNEL_FAILED_REPLY = "模型通道暂时没有返回结果，请稍后重试，或到「模型网关」中检查通道的连通性。";
    static final String TOOLS_UNSUPPORTED_NOTICE = "当前模型通道不支持工具调用，本次已按问答模式回答，无法查询平台数据。";
    static final String CARD_CLAIM_NOTICE = "注意：本轮实际没有生成任何操作卡片，上面关于\"卡片已生成\"的说法有误。如需创建或修改，请让助手重新生成。";
    private static final java.util.regex.Pattern CARD_CLAIM = java.util.regex.Pattern.compile(
            "(已|已经)(为你|为您|帮你|帮您)?生成[^。\n]{0,12}卡片|卡片[^。\n]{0,6}已(经)?生成");

    private final LlmGatewayService gatewayService;
    private final OpenAiCompatibleClient openAiClient;
    private final CustomHttpLlmClient customHttpClient;
    private final AssistantToolRegistry toolRegistry;
    private final AssistantConversationService conversationService;
    private final ContentGuardService contentGuardService;
    private final GuardrailPolicyService policyService;
    private final AuditRecorder auditRecorder;
    private final AssistantActionService actionService;
    private final PlatformDocsService platformDocsService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor;

    public AssistantService(LlmGatewayService gatewayService,
                            OpenAiCompatibleClient openAiClient,
                            CustomHttpLlmClient customHttpClient,
                            AssistantToolRegistry toolRegistry,
                            AssistantConversationService conversationService,
                            ContentGuardService contentGuardService,
                            GuardrailPolicyService policyService,
                            AuditRecorder auditRecorder,
                            AssistantActionService actionService,
                            PlatformDocsService platformDocsService,
                            @Value("${app.assistant.max-concurrent:16}") int maxConcurrent) {
        this.actionService = actionService;
        this.platformDocsService = platformDocsService;
        this.gatewayService = gatewayService;
        this.openAiClient = openAiClient;
        this.customHttpClient = customHttpClient;
        this.toolRegistry = toolRegistry;
        this.conversationService = conversationService;
        this.contentGuardService = contentGuardService;
        this.policyService = policyService;
        this.auditRecorder = auditRecorder;
        AtomicInteger seq = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(Math.max(1, maxConcurrent), r -> {
            Thread t = new Thread(r, "assistant-chat-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 在独立线程中完成对话；调用方必须传入请求线程上的当前用户（ThreadLocal 不会跨线程传递）。 */
    public SseEmitter streamChat(AssistantChatRequest request, CurrentActor actor) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Sink sink = new Sink(emitter);
        emitter.onCompletion(sink::cancel);
        emitter.onTimeout(sink::cancel);
        emitter.onError(e -> sink.cancel());
        try {
            executor.execute(() -> {
                CurrentActor.set(actor);
                try {
                    converse(request, actor, sink);
                } catch (Exception e) {
                    log.error("Assistant stream failed: {}", e.getMessage(), e);
                    sink.send("error", Map.of("message", "助手处理失败，请稍后重试"));
                } finally {
                    CurrentActor.clear();
                    sink.complete();
                }
            });
        } catch (RejectedExecutionException e) {
            sink.send("error", Map.of("message", "助手繁忙，请稍后重试"));
            sink.complete();
        }
        return emitter;
    }

    /**
     * 完成一轮对话。返回本轮的工具上下文（工具调用与生成的待确认操作），供评测使用；
     * 输入被拦截或会话不存在时返回 null。
     */
    ToolContext converse(AssistantChatRequest request, CurrentActor actor, Sink sink) {
        long start = System.currentTimeMillis();
        String mode = request.normalizedMode();
        String message = request.message() == null ? "" : request.message().trim();

        GuardrailPolicy policy = policyService.getEffectivePolicy(actor.getUserId());
        InputGuardResult input = contentGuardService.inspectInput(policy, message);
        if (input.isBlocked()) {
            auditRecorder.record("assistant.input_blocked", "ASSISTANT", request.conversationId(), "BLOCKED",
                    input.getDenyCode(), "MEDIUM", input.getMatchedTerm());
            sink.send("error", Map.of("message", input.getDenyMessage() != null ? input.getDenyMessage() : "输入内容不符合安全策略"));
            return null;
        }
        String effectiveMessage = input.getProcessedMessage() != null ? input.getProcessedMessage() : message;

        AssistantConversation conversation;
        try {
            conversation = conversationService.openOrCreate(request.conversationId(), effectiveMessage, actor);
        } catch (IllegalArgumentException e) {
            sink.send("error", Map.of("message", e.getMessage()));
            return null;
        }
        List<AssistantMessage> history = conversationService.recentHistory(conversation.getId(), HISTORY_LIMIT);
        AssistantMessage userMessage = new AssistantMessage();
        userMessage.setRole(AssistantMessage.ROLE_USER);
        userMessage.setContent(effectiveMessage);
        userMessage.setMode(mode);
        conversationService.append(conversation, userMessage);
        sink.send("start", Map.of("conversationId", conversation.getId()));

        String system = AssistantChatRequest.MODE_CHAT.equals(mode)
                ? AssistantPrompts.chatMode() + docsContext(effectiveMessage)
                : AssistantPrompts.agentMode(actor);
        List<Map<String, Object>> messages = buildMessages(system, history,
                actionService.statusNotes(conversation.getId()), effectiveMessage);

        ToolContext toolContext = ToolContext.of(actor, conversation.getId());
        Turn turn = new Turn(contentGuardService.createStreamingGuard(policy), sink);
        var route = gatewayService.resolveRoute(null);
        if (route.isEmpty()) {
            turn.degraded = true;
            turn.emit(NO_CHANNEL_REPLY);
        } else {
            runWithFailover(route.get(), messages, AssistantChatRequest.MODE_AGENT.equals(mode), toolContext, turn, sink);
        }
        turn.finish();
        if (AssistantChatRequest.MODE_AGENT.equals(mode) && toolContext.createdActions().isEmpty()
                && claimsCardGenerated(turn.shown.toString())) {
            // 模型偶尔会在没有调用写工具的情况下声称"已生成卡片"：明确提示，避免用户误以为操作已在进行
            turn.notice = CARD_CLAIM_NOTICE;
            log.warn("Assistant claimed an action card without calling a write tool (conversation {})", conversation.getId());
        }

        String content = turn.finalContent();
        AssistantMessage reply = new AssistantMessage();
        reply.setRole(AssistantMessage.ROLE_ASSISTANT);
        reply.setContent(content);
        reply.setMode(mode);
        reply.setModel(turn.model);
        reply.setPromptTokens(turn.promptTokens);
        reply.setCompletionTokens(turn.completionTokens);
        reply.setLatencyMs(System.currentTimeMillis() - start);
        reply.setDegraded(turn.degraded);
        reply.setToolCalls(turn.tools.isEmpty() ? null : toJson(turn.tools));
        reply = conversationService.append(conversation, reply);
        actionService.attachMessage(reply.getId(), toolContext.createdActions());

        if (turn.blocked) {
            auditRecorder.record("assistant.output_blocked", "ASSISTANT", conversation.getId(), "BLOCKED",
                    "streaming_violation", "HIGH", turn.guard.getViolatedTerm());
        }

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("conversationId", conversation.getId());
        done.put("messageId", reply.getId());
        done.put("content", content);
        done.put("model", turn.model);
        done.put("latencyMs", reply.getLatencyMs());
        done.put("promptTokens", turn.promptTokens);
        done.put("completionTokens", turn.completionTokens);
        done.put("tools", turn.tools);
        done.put("mode", mode);
        done.put("degraded", turn.degraded);
        done.put("notice", turn.notice);
        done.put("actionIds", toolContext.createdActions().stream().map(AssistantAction::getId).toList());
        sink.send("done", done);
        return toolContext;
    }

    /** 回复中是否声称已经生成了操作卡片 */
    static boolean claimsCardGenerated(String content) {
        return content != null && CARD_CLAIM.matcher(content).find();
    }

    /** 问答模式没有工具：自动检索平台使用文档注入系统提示词；文档未就绪时退回静态功能介绍。 */
    private String docsContext(String question) {
        List<RetrievedChunk> chunks = platformDocsService.search(question, 4);
        if (chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n【平台使用文档参考】回答平台使用问题时以下面的文档内容为准；文档没有提到的，如实说明不确定：\n");
        for (RetrievedChunk chunk : chunks) {
            String content = chunk.content() == null ? "" : chunk.content().trim();
            sb.append("---\n").append(content.length() > 1200 ? content.substring(0, 1200) + "…" : content).append('\n');
        }
        return sb.toString();
    }

    static List<Map<String, Object>> buildMessages(String system, List<AssistantMessage> history, String userMessage) {
        return buildMessages(system, history, List.of(), userMessage);
    }

    /**
     * @param actionNotes 会话中待确认操作的最新状态：用户在卡片上确认后，模型在后续对话中才知道资源已创建及其 ID
     */
    static List<Map<String, Object>> buildMessages(String system, List<AssistantMessage> history,
                                                   List<String> actionNotes, String userMessage) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        for (AssistantMessage item : history) {
            if (item.getContent() == null || item.getContent().isBlank() || Boolean.TRUE.equals(item.getDegraded())) {
                continue;
            }
            String role = AssistantMessage.ROLE_ASSISTANT.equals(item.getRole()) ? "assistant" : "user";
            String content = item.getContent().length() > HISTORY_MESSAGE_MAX_CHARS
                    ? item.getContent().substring(0, HISTORY_MESSAGE_MAX_CHARS) + "…"
                    : item.getContent();
            messages.add(Map.of("role", role, "content", content));
        }
        if (actionNotes != null && !actionNotes.isEmpty()) {
            messages.add(Map.of("role", "system", "content",
                    "本会话中操作卡片的最新状态（以此为准，不要重复生成已执行的操作）：\n- " + String.join("\n- ", actionNotes)));
        }
        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }

    /** 主通道按策略重试；仍失败且尚未向用户输出任何内容时切换到降级通道。 */
    private void runWithFailover(LlmGatewayService.ResolvedRoute route, List<Map<String, Object>> messages,
                                 boolean agentMode, ToolContext toolContext, Turn turn, Sink sink) {
        List<Channel> channels = new ArrayList<>();
        channels.add(new Channel(route.primary(), route.primaryKey()));
        if (route.fallback() != null && gatewayService.hasKey(route.fallback())) {
            channels.add(new Channel(route.fallback(), route.fallbackKey()));
        }
        int attempts = Math.max(1, route.maxRetries() + 1);
        for (Channel channel : channels) {
            for (int attempt = 0; attempt < attempts; attempt++) {
                turn.model = channel.model();
                try {
                    runRounds(channel, route.timeoutMs(), messages, agentMode, toolContext, turn, sink);
                    return;
                } catch (RuntimeException e) {
                    log.warn("Assistant channel [{}] attempt {} failed: {}", channel.provider().getName(), attempt + 1, e.getMessage());
                    // 已输出内容或已生成操作卡片后不再重试，避免同一回复拼接两次结果或重复生成卡片
                    if (turn.hasShownContent() || !toolContext.createdActions().isEmpty() || sink.isCancelled()) {
                        turn.interrupted = true;
                        return;
                    }
                    turn.resetForRetry();
                }
            }
        }
        turn.degraded = true;
        turn.emit(CHANNEL_FAILED_REPLY);
    }

    private void runRounds(Channel channel, int timeoutMs, List<Map<String, Object>> baseMessages,
                           boolean agentMode, ToolContext toolContext, Turn turn, Sink sink) {
        CurrentActor actor = toolContext.actor();
        List<Map<String, Object>> messages = new ArrayList<>(baseMessages);
        ChatGeneration generation = new ChatGeneration();
        generation.setTemperature(agentMode ? 0.3 : 0.5);
        int round = 0;
        while (round < MAX_ROUNDS && !sink.isCancelled() && !turn.blocked) {
            boolean offerTools = agentMode && !turn.toolsUnsupported && round < MAX_ROUNDS - 1;
            List<Map<String, Object>> tools = offerTools ? toolRegistry.definitions(actor) : null;
            OpenAiCompatibleClient.StreamResult result;
            try {
                result = callModel(channel, timeoutMs, messages, tools, generation, turn, sink);
            } catch (RuntimeException e) {
                if (offerTools && !turn.hasShownContent() && looksLikeToolsRejected(e.getMessage())) {
                    log.info("Channel [{}] rejected tool definitions, falling back to chat mode", channel.provider().getName());
                    turn.toolsUnsupported = true;
                    turn.notice = TOOLS_UNSUPPORTED_NOTICE;
                    continue; // 同一轮不带工具重试
                }
                throw e;
            }
            turn.promptTokens += result.promptTokens();
            turn.completionTokens += result.completionTokens();
            if (!offerTools || !result.hasToolCalls() || sink.isCancelled()) {
                return; // 最终回复已在流式回调中推送
            }

            Map<String, Object> assistantCall = new LinkedHashMap<>();
            assistantCall.put("role", "assistant");
            assistantCall.put("content", result.content() == null ? "" : result.content());
            assistantCall.put("tool_calls", result.toolCalls().stream().map(call -> Map.of(
                    "id", call.id(),
                    "type", "function",
                    "function", Map.of("name", call.name(), "arguments", call.arguments())
            )).toList());
            messages.add(assistantCall);

            for (OpenAiCompatibleClient.ToolCall call : result.toolCalls()) {
                String label = toolRegistry.labelOf(call.name());
                sink.send("tool", Map.of("id", call.id(), "name", call.name(), "label", label, "status", "running"));
                int actionsBefore = toolContext.createdActions().size();
                AssistantToolRegistry.Outcome outcome = toolRegistry.execute(call.name(), call.arguments(), toolContext);
                log.info("Assistant tool [{}] ok={} for user [{}]", call.name(), outcome.ok(), actor.getUsername());
                sink.send("tool", Map.of("id", call.id(), "name", call.name(), "label", label,
                        "status", outcome.ok() ? "done" : "failed"));
                // 写工具生成了待确认操作：推送操作卡片
                for (AssistantAction action : toolContext.createdActions().subList(actionsBefore, toolContext.createdActions().size())) {
                    sink.send("action", actionService.card(action));
                }
                turn.tools.add(Map.of("name", call.name(), "label", label, "ok", outcome.ok()));
                toolContext.calls().add(new ToolContext.ToolCallRecord(call.name(), call.arguments(), outcome.ok()));
                messages.add(Map.of("role", "tool", "tool_call_id", call.id(), "content", outcome.content()));
            }
            turn.separateNextRound();
            round++;
        }
    }

    private OpenAiCompatibleClient.StreamResult callModel(Channel channel, int timeoutMs, List<Map<String, Object>> messages,
                                                          List<Map<String, Object>> tools, ChatGeneration generation,
                                                          Turn turn, Sink sink) {
        LlmProvider provider = channel.provider();
        if (provider.getProtocol() == LlmProtocolType.CUSTOM_HTTP) {
            // 自定义 HTTP 协议不支持工具调用与流式：一次性取回后整段推送
            turn.toolsUnsupported = true;
            if (tools != null) {
                turn.notice = TOOLS_UNSUPPORTED_NOTICE;
            }
            OpenAiCompatibleClient.ChatResult result = customHttpClient.chat(provider, messages, generation, timeoutMs);
            String content = result != null && result.content() != null ? result.content() : "";
            turn.emit(content);
            return new OpenAiCompatibleClient.StreamResult(content, List.of(),
                    result != null ? result.promptTokens() : 0, result != null ? result.completionTokens() : 0);
        }
        return openAiClient.streamChat(provider.getBaseUrl(), channel.key(), channel.model(),
                LlmGatewayService.parseCustomHeaders(provider.getCustomConfig()),
                LlmGatewayService.isWebSearchEnabled(provider),
                messages, tools, generation, timeoutMs, turn::emit, () -> sink.isCancelled() || turn.blocked);
    }

    /** 通道拒绝 tools 参数（不支持 Function Calling）的典型报错 */
    static boolean looksLikeToolsRejected(String error) {
        if (error == null) {
            return false;
        }
        String lower = error.toLowerCase();
        boolean clientError = lower.contains("http 400") || lower.contains("http 422") || lower.contains("http 404");
        return clientError && (lower.contains("tool") || lower.contains("function"));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    record Channel(LlmProvider provider, String key) {
        String model() {
            return provider.getDefaultModel();
        }
    }

    /** 一次回复的累积状态：经护栏过滤后推送给前端的内容、token、调用过的工具。 */
    static final class Turn {
        final Sink sink;
        final StreamingSlidingWindowGuard guard;
        final StringBuilder shown = new StringBuilder();
        final List<Map<String, Object>> tools = new ArrayList<>();
        String model;
        int promptTokens;
        int completionTokens;
        boolean degraded;
        boolean blocked;
        boolean interrupted;
        boolean toolsUnsupported;
        String notice;
        private boolean pendingSeparator;

        Turn(StreamingSlidingWindowGuard guard, Sink sink) {
            this.guard = guard;
            this.sink = sink;
        }

        /** 模型增量 → 护栏 → 推送。命中护栏后不再推送任何内容。 */
        void emit(String delta) {
            if (blocked || delta == null || delta.isEmpty()) {
                return;
            }
            String text = delta;
            if (pendingSeparator && !shown.isEmpty()) {
                text = "\n\n" + text;
            }
            pendingSeparator = false;
            List<String> safe = guard.processChunk(text);
            if (isBlocking()) {
                block();
                return;
            }
            push(safe);
        }

        /** 拦截模式下命中即中断；脱敏模式下命中内容已打码，继续输出 */
        private boolean isBlocking() {
            return guard.isViolationDetected() && guard.isBlockOnViolation();
        }

        /** 工具调用前模型已输出的内容与下一轮回复之间空一行 */
        void separateNextRound() {
            pendingSeparator = true;
        }

        void finish() {
            if (blocked) {
                return;
            }
            String rest = guard.flush();
            if (isBlocking()) {
                block();
                return;
            }
            if (rest != null && !rest.isEmpty()) {
                push(List.of(rest));
            }
            if (interrupted) {
                push(List.of("\n\n（回复中断：模型通道异常，请重试）"));
                degraded = true;
            }
        }

        boolean hasShownContent() {
            return !shown.isEmpty();
        }

        /** 通道失败且用户尚未看到内容时，丢弃护栏中缓冲的半截内容与本次尝试的统计后重试 */
        void resetForRetry() {
            promptTokens = 0;
            completionTokens = 0;
            tools.clear();
            pendingSeparator = false;
            guard.flush();
        }

        String finalContent() {
            if (blocked) {
                return shown.isEmpty() ? "（输出命中内容安全策略，已中断）" : shown + "\n\n（输出命中内容安全策略，已中断）";
            }
            String content = AiChatService.cleanAnswer(shown.toString());
            return content == null || content.isBlank() ? "模型没有返回内容，请换个问法重试。" : content;
        }

        private void block() {
            blocked = true;
            sink.send("guardrail", Map.of("message", "输出命中内容安全策略，已中断"));
        }

        private void push(List<String> chunks) {
            for (String chunk : chunks) {
                if (chunk != null && !chunk.isEmpty()) {
                    shown.append(chunk);
                    sink.send("message", Map.of("delta", chunk));
                }
            }
        }
    }

    /** SSE 发送端：前端断开后停止发送，并通过 isCancelled 通知模型读取循环尽快结束。 */
    static class Sink {
        private final SseEmitter emitter;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final ObjectMapper mapper = new ObjectMapper();

        Sink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        synchronized void send(String event, Object data) {
            if (cancelled.get() || completed.get()) {
                return;
            }
            String json;
            try {
                json = mapper.writeValueAsString(data);
            } catch (Exception e) {
                // 序列化失败是程序问题，不是前端断开：记录并跳过该事件，不中断整个对话
                log.error("Failed to serialize assistant SSE event [{}]: {}", event, e.getMessage());
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(json));
            } catch (Exception e) {
                cancelled.set(true);
            }
        }

        void cancel() {
            cancelled.set(true);
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        void complete() {
            if (completed.compareAndSet(false, true)) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
