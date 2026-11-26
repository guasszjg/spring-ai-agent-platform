package com.shimeta.trace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 线程级调用链路采集器：贯穿 AiChatService → KnowledgeBaseService → SpringAiKnowledgeBaseProvider → OpenAiCompatibleClient，
 * 无需修改既有方法签名即可采集各节点起止时间、模型、检索召回、工具调用与异常，并支持实时回调推送 SSE。
 */
public class ChatTraceContext {

    private static final ThreadLocal<ChatTraceContext> CURRENT = new ThreadLocal<>();

    private final String traceId = "tr-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final long startedAt = System.currentTimeMillis();
    private final AtomicInteger seq = new AtomicInteger(0);
    private final List<ChatTraceStep> steps = new ArrayList<>();
    private final Map<String, ChatTraceStep> stepById = new LinkedHashMap<>();
    private final Deque<String> parentStack = new ArrayDeque<>();
    private final Consumer<ChatTraceStep> listener;

    private String agentId;
    private String agentName;
    private String model;
    private String channel;
    private String failoverFrom;
    private String routeNotice;
    private Long firstTokenMs;
    private String activeLlmStepId;
    private Long activeLlmStartMs;
    private String serializedTrace;

    private ChatTraceContext(String agentId, String agentName, Consumer<ChatTraceStep> listener) {
        this.agentId = agentId;
        this.agentName = agentName;
        this.listener = listener;
    }

    public static ChatTraceContext begin(String agentId, String agentName, Consumer<ChatTraceStep> listener) {
        ChatTraceContext ctx = new ChatTraceContext(agentId, agentName, listener);
        CURRENT.set(ctx);
        return ctx;
    }

    public static ChatTraceContext current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSerializedTrace() {
        return serializedTrace;
    }

    public void setSerializedTrace(String serializedTrace) {
        this.serializedTrace = serializedTrace;
    }

    public void setAgentMeta(String agentId, String agentName) {
        if (agentId != null && !agentId.isBlank()) {
            this.agentId = agentId;
        }
        if (agentName != null && !agentName.isBlank()) {
            this.agentName = agentName;
        }
    }

    public void setRouteMeta(String channel, String model, String failoverFrom, String routeNotice) {
        if (channel != null && !channel.isBlank()) {
            this.channel = channel;
        }
        if (model != null && !model.isBlank()) {
            this.model = model;
        }
        if (failoverFrom != null && !failoverFrom.isBlank()) {
            this.failoverFrom = failoverFrom;
        }
        if (routeNotice != null && !routeNotice.isBlank()) {
            this.routeNotice = routeNotice;
        }
    }

    public synchronized void pushParent(String stepId) {
        if (stepId != null && !stepId.isBlank()) {
            parentStack.push(stepId);
        }
    }

    public synchronized void popParent(String stepId) {
        if (!parentStack.isEmpty() && parentStack.peek().equals(stepId)) {
            parentStack.pop();
        }
    }

    public synchronized String currentParentId() {
        return parentStack.peek();
    }

    public synchronized String startStep(String stage, String name, String summary, Map<String, Object> detail) {
        return startStep(stage, name, currentParentId(), summary, detail);
    }

    public synchronized String startStep(String stage, String name, String parentId, String summary, Map<String, Object> detail) {
        String id = "step-" + seq.incrementAndGet();
        long now = System.currentTimeMillis();
        long offset = Math.max(0L, now - startedAt);
        ChatTraceStep step = new ChatTraceStep(id, parentId, stage, name, "running", offset, null, summary, null, detail);
        steps.add(step);
        stepById.put(id, step);
        if ("LLM".equals(stage)) {
            this.activeLlmStepId = id;
            this.activeLlmStartMs = now;
        }
        emit(step);
        return id;
    }

    public synchronized void finishStep(String stepId, String summary, Map<String, Object> detail) {
        ChatTraceStep step = stepById.get(stepId);
        if (step == null) {
            return;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - (startedAt + step.getStartOffsetMs()));
        step.setStatus("ok");
        step.setDurationMs(elapsed);
        if (summary != null && !summary.isBlank()) {
            step.setSummary(summary);
        }
        mergeDetail(step, detail);
        if (stepId.equals(activeLlmStepId)) {
            activeLlmStepId = null;
            activeLlmStartMs = null;
        }
        emit(step);
    }

    public synchronized void failStep(String stepId, String errorMessage, Map<String, Object> detail) {
        ChatTraceStep step = stepById.get(stepId);
        if (step == null) {
            return;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - (startedAt + step.getStartOffsetMs()));
        step.setStatus("error");
        step.setDurationMs(elapsed);
        String msg = truncate(errorMessage, 320);
        step.setError(msg);
        if (step.getSummary() == null || step.getSummary().isBlank()) {
            step.setSummary(msg);
        }
        mergeDetail(step, detail);
        if (stepId.equals(activeLlmStepId)) {
            activeLlmStepId = null;
            activeLlmStartMs = null;
        }
        emit(step);
    }

    public synchronized void recordInstantStep(String stage, String name, String status, String summary, Map<String, Object> detail) {
        String id = "step-" + seq.incrementAndGet();
        long offset = Math.max(0L, System.currentTimeMillis() - startedAt);
        ChatTraceStep step = new ChatTraceStep(id, currentParentId(), stage, name, status, offset, 0L, summary, null, detail);
        steps.add(step);
        stepById.put(id, step);
        emit(step);
    }

    /** 记录首个正文/思考 Token 到达时间（总首字延迟 + 当前 LLM 轮次首字延迟） */
    public synchronized void recordFirstToken() {
        long now = System.currentTimeMillis();
        if (this.firstTokenMs == null) {
            this.firstTokenMs = Math.max(0L, now - startedAt);
        }
        if (activeLlmStepId != null && activeLlmStartMs != null) {
            ChatTraceStep llmStep = stepById.get(activeLlmStepId);
            if (llmStep != null) {
                Map<String, Object> d = llmStep.getDetail();
                if (d == null) {
                    d = new LinkedHashMap<>();
                    llmStep.setDetail(d);
                }
                if (!d.containsKey("ttftMs")) {
                    d.put("ttftMs", Math.max(0L, now - activeLlmStartMs));
                }
            }
        }
    }

    public synchronized ChatTrace snapshotOk(String model, String channel, String failoverFrom, String routeNotice,
                                             int promptTokens, int cachedPromptTokens, int completionTokens) {
        setRouteMeta(channel, model, failoverFrom, routeNotice);
        return buildSnapshot("ok", null, promptTokens, cachedPromptTokens, completionTokens);
    }

    public synchronized ChatTrace snapshotError(String errorMessage) {
        // 将任何仍在运行中的节点标记为异常终止，方便定位卡点
        long now = System.currentTimeMillis();
        for (ChatTraceStep step : steps) {
            if ("running".equals(step.getStatus())) {
                step.setStatus("error");
                step.setDurationMs(Math.max(0L, now - (startedAt + step.getStartOffsetMs())));
                if (step.getError() == null) {
                    step.setError(truncate(errorMessage, 320));
                }
            }
        }
        return buildSnapshot("error", truncate(errorMessage, 320), 0, 0, 0);
    }

    private ChatTrace buildSnapshot(String status, String error, int promptTokens, int cachedPromptTokens, int completionTokens) {
        ChatTrace trace = new ChatTrace();
        trace.setTraceId(traceId);
        trace.setAgentId(agentId);
        trace.setAgentName(agentName);
        trace.setStatus(status);
        trace.setModel(model);
        trace.setChannel(channel);
        trace.setFailoverFrom(failoverFrom);
        trace.setRouteNotice(routeNotice);
        trace.setStartedAt(startedAt);
        trace.setTotalDurationMs(Math.max(0L, System.currentTimeMillis() - startedAt));
        trace.setFirstTokenMs(firstTokenMs);
        if (promptTokens > 0 || completionTokens > 0) {
            trace.setPromptTokens(promptTokens);
            trace.setCachedPromptTokens(cachedPromptTokens);
            trace.setCompletionTokens(completionTokens);
            trace.setTotalTokens(promptTokens + completionTokens);
        }
        trace.setError(error);
        List<ChatTraceStep> copied = new ArrayList<>(steps.size());
        for (ChatTraceStep s : steps) {
            copied.add(s.copy());
        }
        trace.setSteps(copied);
        return trace;
    }

    private void mergeDetail(ChatTraceStep step, Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return;
        }
        Map<String, Object> merged = step.getDetail() != null ? new LinkedHashMap<>(step.getDetail()) : new LinkedHashMap<>();
        merged.putAll(extra);
        step.setDetail(merged);
    }

    private void emit(ChatTraceStep step) {
        if (listener == null) {
            return;
        }
        try {
            listener.accept(step.copy());
        } catch (Exception ignored) {
            // 监听回调异常不能影响主对话流程
        }
    }

    public static String truncate(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen) + "…";
    }
}
