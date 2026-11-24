package com.shimeta.assistant;

import com.shimeta.model.AssistantAction;
import com.shimeta.model.AssistantConversation;
import com.shimeta.model.AssistantMessage;
import com.shimeta.model.ChatGeneration;
import com.shimeta.model.GuardrailPolicy;
import com.shimeta.model.LlmProtocolType;
import com.shimeta.model.LlmProvider;
import com.shimeta.rag.RetrievedChunk;
import com.shimeta.security.CurrentActor;
import com.shimeta.security.guardrail.ContentGuardService;
import com.shimeta.security.guardrail.InputGuardResult;
import com.shimeta.security.guardrail.StreamingSlidingWindowGuard;
import com.shimeta.service.AiChatService;
import com.shimeta.service.AuditRecorder;
import com.shimeta.service.CustomHttpLlmClient;
import com.shimeta.service.GuardrailPolicyService;
import com.shimeta.service.LlmGatewayService;
import com.shimeta.service.OpenAiCompatibleClient;
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
    static final String AGENT_MODE_DISABLED_NOTICE = "当前模型不支持执行模式，已自动切换为问答模式；请在模型网关为助手选择 7B 以上、支持工具调用的模型。";
    static final String CARD_CLAIM_NOTICE = "注意：本轮实际没有生成任何操作卡片，上面关于\"卡片已生成\"的说法有误。如需创建或修改，请让助手重新生成。";
    private static final java.util.regex.Pattern CARD_CLAIM = java.util.regex.Pattern.compile(
            "(已|已经)(为你|为您|帮你|帮您)?生成[^。\n]{0,12}卡片|卡片[^。\n]{0,6}已(经)?(为你|为您|帮你|帮您)?生成");
    /** 宁宽勿严的写操作意图判断：纯查询时不挂载写工具与冗长卡片规则，避免 4096 等小上下文窗口溢出 */
    private static final java.util.regex.Pattern WRITE_INTENT = java.util.regex.Pattern.compile(
            "(?i)(创建|新建|建一|建个|加一|添加|新增|修改|改成|改为|更新|编辑|调整|调成|换成|切换|激活|绑定|解绑|关联|取消关联|"
                    + "停用|启用|开启|关闭|暂停|发布|上线|下线|签发|生成|凭证|api\\s*key|apikey|faq|卡片|套用|存为|登记|刷新|"
                    + "回滚|重建|跑.*评测|运行.*评测|重置|吊销|轮换|重命名|删除|移除|撤)");
    /** Ollama OpenAI 兼容接口未配置 contextWindow 时的保守默认窗口，避免输入顶爆 4096 返回空内容 */
    static final int OLLAMA_DEFAULT_CONTEXT_WINDOW = 4096;

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

        List<String> actionNotes = actionService.statusNotes(conversation.getId());
        var route = gatewayService.resolveAssistantRoute();
        boolean requestedAgentMode = AssistantChatRequest.MODE_AGENT.equals(mode);
        boolean agentModeDowngraded = requestedAgentMode && route.isPresent()
                && !LlmGatewayService.supportsAssistantAgentMode(route.get().route().primary());
        boolean agentMode = requestedAgentMode && !agentModeDowngraded;
        if (agentModeDowngraded) {
            mode = AssistantChatRequest.MODE_CHAT;
        }
        boolean includeWrite = agentMode && mayNeedWriteTools(effectiveMessage, actionNotes);
        java.util.Set<String> prevGroups = agentMode ? conversationGroups.get(conversation.getId()) : null;
        AssistantToolRouter.ToolSelection selection = agentMode
                ? toolRouter.route(effectiveMessage, request.context(), prevGroups, actor, includeWrite)
                : AssistantToolRouter.ToolSelection.ALL;
        if (agentMode && !selection.groupIds().isEmpty()) {
            conversationGroups.put(conversation.getId(), selection.groupIds());
        }

        ToolContext toolContext = ToolContext.of(actor, conversation.getId());
        Turn turn = new Turn(contentGuardService.createStreamingGuard(policy), sink);
        if (agentModeDowngraded) {
            turn.notice = AGENT_MODE_DISABLED_NOTICE;
        }
        Integer routeWindow = route.map(r -> effectiveContextWindow(r.route().primary())).orElse(null);

        String system = (AssistantChatRequest.MODE_CHAT.equals(mode)
                ? AssistantPrompts.chatMode(actor) + docsContext(effectiveMessage, actor, routeWindow)
                : AssistantPrompts.agentMode(actor, includeWrite)) + pageContext(request.context(), actor);
        List<Map<String, Object>> messages = buildMessages(system, history, actionNotes, effectiveMessage);

        if (route.isEmpty()) {
            turn.degraded = true;
            turn.emit(NO_CHANNEL_REPLY);
        } else {
            runWithFailover(route.get(), messages, agentMode, includeWrite, selection, toolContext, turn, sink);
        }
        turn.finish();

        String content = turn.finalContent();
        if (!turn.blocked && !turn.degraded) {
            int successfulToolCalls = (int) toolContext.calls().stream().filter(ToolContext.ToolCallRecord::ok).count();
            AssistantAnswerVerifier.VerificationResult verification = answerVerifier.verify(
                    new AssistantAnswerVerifier.VerificationInput(
                            conversation.getId(), agentMode, turn.degraded, turn.blocked, turn.toolsUnsupported,
                            effectiveMessage, content, successfulToolCalls,
                            toolContext.createdActions().size(), toolContext.allowedLinks()));
            content = verification.cleanedContent();
            if (verification.extraNotice() != null) {
                turn.notice = turn.notice == null || turn.notice.isBlank()
                        ? verification.extraNotice()
                        : turn.notice + " " + verification.extraNotice();
            }
            if (verification.hasFlags()) {
                turn.verificationFlags.addAll(verification.flags());
            }
        }
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
        reply.setVerificationFlags(turn.verificationFlags.isEmpty() ? null : toJson(turn.verificationFlags));
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
        if (!turn.verificationFlags.isEmpty()) {
            done.put("verificationFlags", turn.verificationFlags);
        }
        sink.send("done", done);
        return toolContext;
    }

    private AssistantQueryTools queryTools;
    private AssistantAnswerVerifier answerVerifier = new AssistantAnswerVerifier();
    private AssistantToolRouter toolRouter = new AssistantToolRouter();
    /** 会话级工具组记忆（LRU 上限 500），支持多轮追问时自动继承上一轮语义工具组 */
    private final Map<String, java.util.Set<String>> conversationGroups = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, java.util.Set<String>> eldest) {
                    return size() > 500;
                }
            });

    /** 可选注入（setter 方式，避免改动已有构造器签名）：用于把页面上下文解析成资源名称。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setQueryTools(AssistantQueryTools queryTools) {
        this.queryTools = queryTools;
    }

    /** 可选注入：后置防编造回答校验器（默认使用内置单例，避免影响现有单测构造）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAnswerVerifier(AssistantAnswerVerifier answerVerifier) {
        if (answerVerifier != null) {
            this.answerVerifier = answerVerifier;
        }
    }

    /** 可选注入：语义工具路由器（支持向量相似度选组与多轮组继承）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setToolRouter(AssistantToolRouter toolRouter) {
        if (toolRouter != null) {
            this.toolRouter = toolRouter;
        }
    }

    /** 页面上下文（P3）：用户在哪个页面、正在看哪个资源，让模型理解"这个智能体""当前知识库" */
    private String pageContext(Map<String, String> context, CurrentActor actor) {
        if (queryTools == null || context == null) {
            return "";
        }
        try {
            String description = queryTools.describeContext(context, actor);
            return description == null ? "" : "\n\n【页面上下文】" + description;
        } catch (RuntimeException e) {
            log.debug("Failed to describe assistant page context: {}", e.getMessage());
            return "";
        }
    }

    /** 回复中是否声称已经生成了操作卡片 */
    static boolean claimsCardGenerated(String content) {
        return content != null && CARD_CLAIM.matcher(content).find();
    }

    /** 问答模式没有工具：按当前通道上下文窗口计算预算，自动检索平台使用文档注入系统提示词。 */
    private String docsContext(String question, CurrentActor actor, Integer contextWindow) {
        DocsBudget budget = docsBudgetForWindow(contextWindow);
        List<RetrievedChunk> chunks = platformDocsService.search(question, budget.topK(), actor);
        if (chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n【平台使用文档参考】回答平台使用问题时以下面的文档内容为准；文档没有提到的，如实说明不确定：\n");
        int usedChars = 0;
        for (RetrievedChunk chunk : chunks) {
            String content = chunk.content() == null ? "" : chunk.content().trim();
            if (content.isEmpty()) {
                continue;
            }
            int remaining = budget.maxTotalChars() - usedChars;
            if (remaining < 120) {
                break;
            }
            int cap = Math.min(budget.maxCharsPerChunk(), remaining);
            String snippet = content.length() > cap ? content.substring(0, cap) + "…" : content;
            sb.append("---\n").append(snippet).append('\n');
            usedChars += snippet.length();
        }
        return usedChars == 0 ? "" : sb.toString();
    }

    /** 按通道上下文窗口计算问答模式可注入的文档片段预算：小窗口少注几段，大窗口多注几段。 */
    static DocsBudget docsBudgetForWindow(Integer window) {
        if (window != null && window > 0) {
            if (window <= 4096) {
                return new DocsBudget(2, 600, 1200);
            }
            if (window <= 8192) {
                return new DocsBudget(3, 900, 2500);
            }
            if (window > 32768) {
                return new DocsBudget(6, 1500, 8000);
            }
        }
        return new DocsBudget(4, 1200, 4800);
    }

    record DocsBudget(int topK, int maxCharsPerChunk, int maxTotalChars) {
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
        // 严格保证只有首条 role=system：vLLM / llama.cpp / Qwen3 Jinja 模板禁止在对话中间出现第二条 system 消息
        String effectiveSystem = system;
        if (actionNotes != null && !actionNotes.isEmpty()) {
            effectiveSystem = (effectiveSystem == null ? "" : effectiveSystem)
                    + "\n\n【本会话历史操作卡片状态】（仅用于了解哪些已执行成功、哪些可作为依赖；不要重复生成已执行成功的操作）：\n- "
                    + String.join("\n- ", actionNotes)
                    + "\n注意：如果用户本轮再次明确要求创建、修改或重新生成操作，且对应操作尚未执行成功（如仍在等待确认、已过期、已取消或失败），"
                    + "必须重新调用对应的写工具生成一张新的待确认卡片，不要只口头回复\"卡片已经生成/等待确认\"。";
        }
        messages.add(Map.of("role", "system", "content", effectiveSystem));
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
        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }

    /** 将系统级纠正指令并入首条 system 消息，避免在消息尾部追加第二条 system 导致本地 Jinja 模板报 400。 */
    private static List<Map<String, Object>> withAppendedSystemInstruction(List<Map<String, Object>> messages, String instruction) {
        List<Map<String, Object>> copy = new ArrayList<>(messages);
        if (!copy.isEmpty() && "system".equals(copy.get(0).get("role"))) {
            String prev = String.valueOf(copy.get(0).get("content"));
            copy.set(0, Map.of("role", "system", "content", prev + "\n\n" + instruction));
        } else {
            copy.add(0, Map.of("role", "system", "content", instruction));
        }
        return copy;
    }

    /** 主通道按策略重试；仍失败且尚未向用户输出任何内容时切换到降级通道。 */
    private void runWithFailover(LlmGatewayService.AssistantRoute assistantRoute, List<Map<String, Object>> messages,
                                 boolean agentMode, boolean includeWrite, AssistantToolRouter.ToolSelection selection,
                                 ToolContext toolContext, Turn turn, Sink sink) {
        List<Channel> channels = new ArrayList<>();
        LlmGatewayService.ResolvedRoute route = assistantRoute.route();
        // 助手专用模型只作用于主通道；降级通道使用它自己的默认模型
        channels.add(new Channel(route.primary(), route.primaryKey(), assistantRoute.model()));
        if (route.fallback() != null && gatewayService.hasKey(route.fallback())) {
            channels.add(new Channel(route.fallback(), route.fallbackKey(), null));
        }
        int attempts = Math.max(1, route.maxRetries() + 1);
        for (Channel channel : channels) {
            for (int attempt = 0; attempt < attempts; attempt++) {
                turn.model = channel.model();
                try {
                    runRounds(channel, route.timeoutMs(), messages, agentMode, includeWrite, selection, toolContext, turn, sink);
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
                           boolean agentMode, boolean includeWrite, AssistantToolRouter.ToolSelection selection,
                           ToolContext toolContext, Turn turn, Sink sink) {
        CurrentActor actor = toolContext.actor();
        Integer window = effectiveContextWindow(channel.provider());
        java.util.Set<String> allowedRead = selection != null ? selection.allowedReadTools() : null;
        java.util.Set<String> allowedWrite = selection != null ? selection.allowedWriteTools() : null;
        List<Map<String, Object>> initialTools = agentMode
                ? toolRegistry.definitions(actor, includeWrite, allowedRead, allowedWrite) : null;
        List<Map<String, Object>> messages = fitMessagesForWindow(baseMessages, initialTools, window);
        ChatGeneration generation = new ChatGeneration();
        generation.setTemperature(agentMode ? 0.3 : 0.5);
        generation.setMaxTokens(window != null && window > 0 ? Math.min(2048, Math.max(512, window / 3)) : 2048);
        int round = 0;
        while (round < MAX_ROUNDS && !sink.isCancelled() && !turn.blocked && !turn.repetitionLoopDetected) {
            boolean offerTools = agentMode && !turn.toolsUnsupported && round < MAX_ROUNDS - 1;
            List<Map<String, Object>> tools = offerTools
                    ? toolRegistry.definitions(actor, includeWrite, allowedRead, allowedWrite) : null;
            boolean hasTools = offerTools && tools != null && !tools.isEmpty();
            boolean guardReadEmptyToolCall = round == 0 && hasTools && !includeWrite
                    && selection != null && selection.hitsReadDataGroup()
                    && LlmGatewayService.supportsToolChoiceRequired(channel.provider());
            boolean guardWriteCardClaim = round == 0 && hasTools && includeWrite && !actor.isViewer();
            boolean bufferRoundZero = guardReadEmptyToolCall || guardWriteCardClaim;
            List<String> bufferedDeltas = bufferRoundZero ? new ArrayList<>() : null;
            java.util.function.Consumer<String> deltaSink = bufferRoundZero ? bufferedDeltas::add : turn::emit;

            OpenAiCompatibleClient.StreamResult result;
            try {
                result = callModel(channel, timeoutMs, messages, tools, generation, turn, sink, deltaSink);
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

            boolean shouldRetryRead = guardReadEmptyToolCall && !result.hasToolCalls() && !sink.isCancelled() && !turn.blocked;
            boolean shouldRetryWrite = guardWriteCardClaim && !result.hasToolCalls() && !sink.isCancelled() && !turn.blocked
                    && claimsCardGenerated(result.content());
            if (shouldRetryRead || shouldRetryWrite) {
                ChatGeneration forcedGeneration = generation.copy();
                List<Map<String, Object>> retryMessages = messages;
                if (shouldRetryWrite) {
                    // 写操作轮次模型未调工具却口头声称"卡片已生成"时，纠正并强制重试一次，确保真正调用写工具生成卡片
                    log.info("Assistant round 0 claimed action card without calling write tool, retrying once");
                    retryMessages = withAppendedSystemInstruction(messages,
                            "系统纠正：你刚才没有调用任何写工具，本轮实际未生成任何操作卡片。"
                                    + "若用户要求创建、修改、运行或重新生成操作，请立即调用对应的写工具生成新的待确认卡片；"
                                    + "不要仅口头回复\"卡片已经生成/等待确认\"。");
                    if (LlmGatewayService.supportsToolChoiceRequired(channel.provider())) {
                        forcedGeneration.setToolChoice("required");
                    }
                } else {
                    // 4.5：查询类问题第 1 轮跳过调工具时，带 tool_choice=required 强制重试一次（避免编造数据）
                    log.info("Assistant round 0 skipped tools on read-data query (groups={}), retrying once with tool_choice=required",
                            selection.groupIds());
                    forcedGeneration.setToolChoice("required");
                }
                try {
                    OpenAiCompatibleClient.StreamResult retryResult = callModel(
                            channel, timeoutMs, retryMessages, tools, forcedGeneration, turn, sink, turn::emit);
                    turn.promptTokens += retryResult.promptTokens();
                    turn.completionTokens += retryResult.completionTokens();
                    result = retryResult;
                } catch (RuntimeException retryEx) {
                    if (!turn.hasShownContent()) {
                        log.info("Channel [{}] rejected forced retry ({}), flushing initial reply",
                                channel.provider().getName(), retryEx.getMessage());
                        for (String delta : bufferedDeltas) {
                            turn.emit(delta);
                        }
                        return;
                    }
                    throw retryEx;
                }
            } else if (bufferRoundZero && bufferedDeltas != null && !bufferedDeltas.isEmpty()) {
                for (String delta : bufferedDeltas) {
                    turn.emit(delta);
                }
            }

            if (!offerTools || !result.hasToolCalls() || sink.isCancelled() || turn.repetitionLoopDetected) {
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
                String argSummary = summarizeToolArgs(call.arguments());
                Map<String, Object> runningEvent = new LinkedHashMap<>();
                runningEvent.put("id", call.id());
                runningEvent.put("name", call.name());
                runningEvent.put("label", label);
                runningEvent.put("status", "running");
                if (argSummary != null) {
                    runningEvent.put("detail", argSummary);
                }
                sink.send("tool", runningEvent);
                int actionsBefore = toolContext.createdActions().size();
                AssistantToolRegistry.Outcome outcome = toolRegistry.execute(call.name(), call.arguments(), toolContext);
                log.info("Assistant tool [{}] ok={} for user [{}]", call.name(), outcome.ok(), actor.getUsername());
                String resultSummary = summarizeToolOutcome(outcome, argSummary);
                Map<String, Object> doneEvent = new LinkedHashMap<>();
                doneEvent.put("id", call.id());
                doneEvent.put("name", call.name());
                doneEvent.put("label", label);
                doneEvent.put("status", outcome.ok() ? "done" : "failed");
                if (resultSummary != null) {
                    doneEvent.put("detail", resultSummary);
                }
                sink.send("tool", doneEvent);
                // 写工具生成了待确认操作：推送操作卡片
                for (AssistantAction action : toolContext.createdActions().subList(actionsBefore, toolContext.createdActions().size())) {
                    sink.send("action", actionService.card(action));
                }
                Map<String, Object> toolRecord = new LinkedHashMap<>();
                toolRecord.put("name", call.name());
                toolRecord.put("label", label);
                toolRecord.put("ok", outcome.ok());
                if (resultSummary != null) {
                    toolRecord.put("detail", resultSummary);
                }
                turn.tools.add(toolRecord);
                toolContext.calls().add(new ToolContext.ToolCallRecord(call.name(), call.arguments(), outcome.ok()));
                if (outcome.ok()) {
                    toolContext.allowedLinks().addAll(AssistantAnswerVerifier.extractLinksFromJson(outcome.content()));
                }
                String toolContent = trimToolContentForWindow(outcome.content(), messages, tools, window);
                messages.add(Map.of("role", "tool", "tool_call_id", call.id(), "content", toolContent));
            }
            turn.separateNextRound();
            round++;
        }
    }

    /** 判断本轮是否可能涉及写操作：纯查询或纯寒暄不挂载写工具，节省上下文并提高选工具准确率 */
    static boolean mayNeedWriteTools(String message, List<String> actionNotes) {
        if (AssistantToolRouter.isPureChitchat(message)) {
            return false;
        }
        if (actionNotes != null && !actionNotes.isEmpty()) {
            return true;
        }
        return message != null && WRITE_INTENT.matcher(message).find();
    }

    /**
     * 单轮纯查询时按问题领域收窄只读工具集合（2–4 个），避免一次塞入 12 个工具导致本地小模型跳过工具调用而编造答案。
     * 多域问题取并集；未命中明确领域或超过 6 个工具时返回 null（下发全量只读工具）。
     */
    static java.util.Set<String> selectReadTools(String message, Map<String, String> context) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String text = message.toLowerCase();
        java.util.Set<String> selected = new java.util.LinkedHashSet<>();

        if (text.contains("技能") || text.contains("skill")) {
            selected.add("list_skills");
        }
        if (text.contains("mcp")) {
            selected.add("list_mcp_servers");
        }
        if (text.matches(".*(用量|成本|token|花费|花了|排行|最多|分布|统计|调用量|调用次数).*")) {
            selected.add("get_usage_summary");
            selected.add("list_agents");
        }
        if (text.matches(".*(文档|索引|切片|上传).*") && !text.contains("使用文档") && !text.contains("平台文档")) {
            selected.add("list_knowledge_documents");
            selected.add("list_knowledge_bases");
        }
        if (text.matches(".*(知识库|kb|faq|rag|dify).*")) {
            selected.add("list_knowledge_bases");
            selected.add("list_knowledge_documents");
        }
        if (text.matches(".*(通道|网关|向量模型|embedding|rerank|ocr|连通|测试连接|通不通|能不能用).*")) {
            selected.add("get_gateway_status");
            selected.add("test_gateway_channel");
        }
        if (text.matches(".*(诊断|排查|没回复|没有回复|回答不对|不准确|检索不到|报错|异常|为什么).*")) {
            selected.add("diagnose_agent");
            selected.add("get_agent_detail");
            selected.add("list_knowledge_bases");
            selected.add("list_knowledge_documents");
            selected.add("get_gateway_status");
            selected.add("search_platform_docs");
        }
        if (text.contains("模板") || text.contains("template")) {
            selected.add("list_templates");
        }
        if (text.contains("智能体") || text.contains("agent")) {
            selected.add("list_agents");
            selected.add("get_agent_detail");
            selected.add("diagnose_agent");
        }
        if (text.matches(".*(怎么|如何|在哪里|在哪|什么意思|区别|角色|权限|开放|api|401|403|状态).*")) {
            selected.add("search_platform_docs");
        }

        if (selected.isEmpty()) {
            return null;
        }
        if (context != null) {
            String resType = context.get("resourceType");
            if ("AGENT".equalsIgnoreCase(resType)) {
                selected.add("get_agent_detail");
                selected.add("diagnose_agent");
            } else if ("KNOWLEDGE_BASE".equalsIgnoreCase(resType)) {
                selected.add("list_knowledge_bases");
                selected.add("list_knowledge_documents");
            }
        }
        return selected.size() <= 6 ? selected : null;
    }

    static Integer effectiveContextWindow(LlmProvider provider) {
        if (provider == null) {
            return null;
        }
        if (provider.getContextWindow() != null && provider.getContextWindow() > 0) {
            return provider.getContextWindow();
        }
        if (provider.getProtocol() == LlmProtocolType.OLLAMA) {
            return OLLAMA_DEFAULT_CONTEXT_WINDOW;
        }
        return null;
    }

    /** 按通道上下文窗口从最早的历史对话开始裁剪，保证系统提示、卡片备注与当前提问完整保留 */
    List<Map<String, Object>> fitMessagesForWindow(List<Map<String, Object>> baseMessages,
                                                   List<Map<String, Object>> tools, Integer window) {
        List<Map<String, Object>> fitted = new ArrayList<>(baseMessages);
        if (window == null || window <= 0 || fitted.size() <= 2) {
            return fitted;
        }
        int outputReserve = Math.min(window / 4, 1500);
        int inputBudget = (int) ((window - outputReserve) * 0.85);
        int toolTokens = tools == null || tools.isEmpty() ? 0 : AiChatService.estimateTokens(toJson(tools));

        // index 0 为系统提示；末尾为当前 user（及可能紧贴其前的 actionNotes system 消息），中间为可裁剪的历史消息
        int tailStart = fitted.size() - 1;
        if (tailStart >= 2 && "system".equals(fitted.get(tailStart - 1).get("role"))) {
            tailStart--;
        }
        int fixedTokens = toolTokens + estimateMessageTokens(fitted.get(0));
        for (int i = tailStart; i < fitted.size(); i++) {
            fixedTokens += estimateMessageTokens(fitted.get(i));
        }
        int historyBudget = Math.max(0, inputBudget - fixedTokens);
        List<Map<String, Object>> history = new ArrayList<>(fitted.subList(1, tailStart));
        int historyTokens = history.stream().mapToInt(this::estimateMessageTokens).sum();
        int drop = 0;
        while (drop < history.size() && (historyTokens > historyBudget
                || "assistant".equals(history.get(drop).get("role")))) {
            historyTokens -= estimateMessageTokens(history.get(drop));
            drop++;
        }
        if (drop == 0) {
            return fitted;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(fitted.get(0));
        result.addAll(history.subList(drop, history.size()));
        result.addAll(fitted.subList(tailStart, fitted.size()));
        log.info("Assistant context window {}: trimmed history {} -> {} messages (tool ~{} tokens)",
                window, history.size(), history.size() - drop, toolTokens);
        return result;
    }

    private String trimToolContentForWindow(String content, List<Map<String, Object>> messages,
                                            List<Map<String, Object>> tools, Integer window) {
        if (content == null || window == null || window <= 0 || window > 8192) {
            return content;
        }
        int used = messages.stream().mapToInt(this::estimateMessageTokens).sum()
                + (tools == null || tools.isEmpty() ? 0 : AiChatService.estimateTokens(toJson(tools)));
        int reserve = Math.min(window / 4, 1200);
        int remainTokens = Math.max(400, window - reserve - used);
        int maxChars = Math.min(AssistantToolRegistry.MAX_RESULT_CHARS, Math.max(900, remainTokens * 2));
        if (content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars) + "…（受当前模型上下文窗口限制已精简）";
    }

    private int estimateMessageTokens(Map<String, Object> msg) {
        Object content = msg.get("content");
        int tokens = AiChatService.estimateTokens(content == null ? "" : String.valueOf(content)) + 6;
        if (msg.containsKey("tool_calls")) {
            tokens += AiChatService.estimateTokens(toJson(msg.get("tool_calls")));
        }
        return tokens;
    }

    private OpenAiCompatibleClient.StreamResult callModel(Channel channel, int timeoutMs, List<Map<String, Object>> messages,
                                                          List<Map<String, Object>> tools, ChatGeneration generation,
                                                          Turn turn, Sink sink, java.util.function.Consumer<String> deltaConsumer) {
        LlmProvider provider = channel.provider();
        generation.setProviderProtocol(provider.getProtocol());
        if (provider.getProtocol() == LlmProtocolType.CUSTOM_HTTP) {
            // 自定义 HTTP 协议不支持工具调用与流式：一次性取回后整段推送
            turn.toolsUnsupported = true;
            if (tools != null) {
                turn.notice = TOOLS_UNSUPPORTED_NOTICE;
            }
            OpenAiCompatibleClient.ChatResult result = customHttpClient.chat(provider, messages, generation, timeoutMs);
            String content = result != null && result.content() != null ? result.content() : "";
            deltaConsumer.accept(content);
            return new OpenAiCompatibleClient.StreamResult(content, List.of(),
                    result != null ? result.promptTokens() : 0, result != null ? result.completionTokens() : 0);
        }
        return openAiClient.streamChat(provider.getBaseUrl(), channel.key(), channel.model(),
                LlmGatewayService.parseCustomHeaders(provider.getCustomConfig()),
                LlmGatewayService.isWebSearchEnabled(provider),
                messages, tools, generation, timeoutMs, deltaConsumer,
                () -> sink.isCancelled() || turn.blocked || turn.repetitionLoopDetected);
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

    /** 提取简短、脱敏的工具入参摘要，用于前端工具胶囊展示 */
    String summarizeToolArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            var node = objectMapper.readTree(argumentsJson);
            if (node == null || !node.isObject() || node.isEmpty()) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            var fields = node.fields();
            while (fields.hasNext() && parts.size() < 3) {
                var entry = fields.next();
                String key = entry.getKey();
                String lower = key.toLowerCase();
                if (lower.contains("secret") || lower.contains("key") || lower.contains("token")
                        || lower.contains("password") || lower.equals("systemprompt") || lower.equals("answer")) {
                    continue;
                }
                var val = entry.getValue();
                if (val == null || val.isNull() || val.isContainerNode()) {
                    continue;
                }
                String text = val.asText("").trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (text.length() > 20) {
                    text = text.substring(0, 20) + "…";
                }
                parts.add(text);
            }
            if (parts.isEmpty()) {
                return null;
            }
            String joined = String.join(" / ", parts);
            return joined.length() > 48 ? joined.substring(0, 48) + "…" : joined;
        } catch (Exception e) {
            return null;
        }
    }

    /** 提取简短的工具执行结果摘要，方便用户判断查询是否命中 */
    String summarizeToolOutcome(AssistantToolRegistry.Outcome outcome, String fallbackDetail) {
        if (outcome == null || outcome.content() == null || outcome.content().isBlank()) {
            return fallbackDetail;
        }
        try {
            var node = objectMapper.readTree(outcome.content());
            if (node == null || !node.isObject()) {
                return fallbackDetail;
            }
            if (!outcome.ok()) {
                String err = node.path("error").asText("").trim();
                if (!err.isEmpty()) {
                    return err.length() > 40 ? err.substring(0, 40) + "…" : err;
                }
                return "执行失败";
            }
            if (node.has("conclusion") && !node.path("conclusion").asText("").isBlank()) {
                String c = node.path("conclusion").asText("").trim();
                return c.length() > 36 ? c.substring(0, 36) + "…" : c;
            }
            if (node.has("range") && node.has("totalTokens")) {
                String range = node.path("range").asText("");
                int modelCount = node.path("modelUsageRanking").isArray() ? node.path("modelUsageRanking").size() : 0;
                return modelCount > 0 ? range + " · " + modelCount + " 个模型" : range;
            }
            if (node.has("hitCount") && node.path("hitCount").canConvertToInt()) {
                return "命中 " + node.path("hitCount").asInt() + " 条切片";
            }
            if (node.has("total") && node.path("total").canConvertToInt()) {
                return "共 " + node.path("total").asInt() + " 条";
            }
            if (node.has("serverCount") && node.path("serverCount").canConvertToInt()) {
                return node.path("serverCount").asInt() + " 个服务 / " + node.path("enabledToolCount").asInt() + " 个启用工具";
            }
            if (node.has("availableToolCount") && node.path("availableToolCount").canConvertToInt()) {
                return node.path("availableToolCount").asInt() + " 个可用工具";
            }
            if (node.has("items") && node.path("items").isArray()) {
                return "命中 " + node.path("items").size() + " 条";
            }
            return fallbackDetail;
        } catch (Exception e) {
            return fallbackDetail;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    record Channel(LlmProvider provider, String key, String modelOverride) {
        String model() {
            return modelOverride != null && !modelOverride.isBlank() ? modelOverride : provider.getDefaultModel();
        }
    }

    /** 一次回复的累积状态：经护栏过滤后推送给前端的内容、token、调用过的工具。 */
    static final class Turn {
        final Sink sink;
        final StreamingSlidingWindowGuard guard;
        final StringBuilder shown = new StringBuilder();
        final List<Map<String, Object>> tools = new ArrayList<>();
        final List<String> verificationFlags = new ArrayList<>();
        String model;
        int promptTokens;
        int completionTokens;
        boolean degraded;
        boolean blocked;
        boolean interrupted;
        boolean toolsUnsupported;
        volatile boolean repetitionLoopDetected;
        String notice;
        private boolean pendingSeparator;
        private int lastRepetitionCheckLen;

        Turn(StreamingSlidingWindowGuard guard, Sink sink) {
            this.guard = guard;
            this.sink = sink;
        }

        /** 模型增量 → 护栏 → 推送。命中护栏或检测到复读死循环后不再推送任何内容。 */
        void emit(String delta) {
            if (blocked || repetitionLoopDetected || delta == null || delta.isEmpty()) {
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
            if (!repetitionLoopDetected) {
                String rest = guard.flush();
                if (isBlocking()) {
                    block();
                    return;
                }
                if (rest != null && !rest.isEmpty()) {
                    push(List.of(rest));
                }
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
            repetitionLoopDetected = false;
            lastRepetitionCheckLen = 0;
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
                if (repetitionLoopDetected) {
                    return;
                }
                if (chunk != null && !chunk.isEmpty()) {
                    shown.append(chunk);
                    if (shown.length() - lastRepetitionCheckLen >= 24) {
                        lastRepetitionCheckLen = shown.length();
                        int cutIdx = detectRepetitionCutIndex(shown);
                        if (cutIdx >= 0) {
                            repetitionLoopDetected = true;
                            shown.setLength(cutIdx);
                            log.warn("Assistant streaming repetition loop detected at {} chars, truncated to {}",
                                    lastRepetitionCheckLen, cutIdx);
                            return;
                        }
                    }
                    sink.send("message", Map.of("delta", chunk));
                }
            }
        }
    }

    /**
     * 检测模型流式输出是否陷入连续复读死循环（本地 Qwen/Ollama 模型低温度或强制工具调用下偶发退化）：
     * <ul>
     *   <li>长度 60~500 的段落紧邻重复 2 次（允许中间隔少量空白/换行）；或</li>
     *   <li>长度 20~59 的短句紧邻重复 3 次。</li>
     * </ul>
     * 命中时返回应截断位置（保留首份，裁掉重复尾部），未命中返回 -1。
     */
    static int detectRepetitionCutIndex(CharSequence text) {
        if (text == null) {
            return -1;
        }
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (end < 60) {
            return -1;
        }
        int maxUnit = Math.min(500, end / 2);
        for (int unitLen = 60; unitLen <= maxUnit; unitLen++) {
            int secondStart = end - unitLen;
            int firstEnd = skipTrailingWs(text, secondStart, 6);
            int firstStart = firstEnd - unitLen;
            if (firstStart >= 0 && regionEquals(text, firstStart, secondStart, unitLen)) {
                return firstEnd;
            }
        }
        int maxShort = Math.min(59, end / 3);
        for (int unitLen = 20; unitLen <= maxShort; unitLen++) {
            int thirdStart = end - unitLen;
            int secondEnd = skipTrailingWs(text, thirdStart, 6);
            int secondStart = secondEnd - unitLen;
            if (secondStart < 0 || !regionEquals(text, secondStart, thirdStart, unitLen)) {
                continue;
            }
            int firstEnd = skipTrailingWs(text, secondStart, 6);
            int firstStart = firstEnd - unitLen;
            if (firstStart >= 0 && regionEquals(text, firstStart, secondStart, unitLen)) {
                return firstEnd;
            }
        }
        return -1;
    }

    private static int skipTrailingWs(CharSequence text, int pos, int maxSkip) {
        int i = pos;
        int skipped = 0;
        while (i > 0 && skipped < maxSkip && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
            skipped++;
        }
        return i;
    }

    private static boolean regionEquals(CharSequence text, int startA, int startB, int len) {
        for (int i = 0; i < len; i++) {
            if (text.charAt(startA + i) != text.charAt(startB + i)) {
                return false;
            }
        }
        return true;
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
