package com.shimeta.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.model.ChatRequest;
import com.shimeta.model.ChatResponse;
import com.shimeta.security.CurrentActor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 调试页的流式对话：模型边生成边推送（message 事件），结束时推送 message_end（完整回复、模型、耗时、Token、工具调用）。
 * 与非流式接口走同一条对话链路（记忆、思考模式、工具、知识库、会话记录都一致）。
 */
@Service
public class AgentChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatStreamService.class);
    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final AiChatService aiChatService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "debug-chat-stream");
        t.setDaemon(true);
        return t;
    });

    public AgentChatStreamService(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public SseEmitter stream(ChatRequest request, CurrentActor actor) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        executor.execute(() -> {
            CurrentActor.set(actor);
            StreamingCitationStripper stripper = new StreamingCitationStripper();
            AtomicBoolean streamed = new AtomicBoolean(false);
            try {
                ChatResponse response = aiChatService.chat(request, new AiChatService.StreamSink() {
                    @Override
                    public void onDelta(String text) {
                        streamed.set(true);
                        sendAnswer(emitter, closed, stripper.push(text));
                    }

                    @Override
                    public boolean cancelled() {
                        return closed.get();
                    }
                });
                // 通道不支持流式或走了兜底回复时，拿到完整回复后一次性推送
                sendAnswer(emitter, closed, streamed.get() ? stripper.flush() : response.getReply());
                Map<String, Object> end = new LinkedHashMap<>();
                end.put("reply", response.getReply());
                end.put("model", response.getModel());
                end.put("latencyMs", response.getLatencyMs());
                end.put("tokensUsed", response.getTokensUsed());
                end.put("toolCalled", response.getToolCalled());
                end.put("conversationId", response.getConversationId());
                end.put("degraded", Boolean.TRUE.equals(response.getDegraded()));
                send(emitter, closed, "message_end", end);
                emitter.complete();
            } catch (IllegalStateException | IllegalArgumentException e) {
                send(emitter, closed, "error", Map.of("message", e.getMessage() == null ? "对话失败" : e.getMessage()));
                emitter.complete();
            } catch (Exception e) {
                log.error("调试页流式对话失败: {}", e.getMessage(), e);
                send(emitter, closed, "error", Map.of("message", "当前无法完成模型调用，请检查模型网关配置或稍后重试"));
                emitter.complete();
            } finally {
                CurrentActor.clear();
            }
        });
        return emitter;
    }

    private void sendAnswer(SseEmitter emitter, AtomicBoolean closed, String text) {
        if (text != null && !text.isEmpty()) {
            send(emitter, closed, "message", Map.of("answer", text));
        }
    }

    private void send(SseEmitter emitter, AtomicBoolean closed, String event, Map<String, Object> payload) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            // 页面已关闭或切换：停止推送，并通过 cancelled() 让模型停止生成
            closed.set(true);
        }
    }
}
