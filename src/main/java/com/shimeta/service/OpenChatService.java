package com.shimeta.service;

import com.shimeta.config.PublicUrlResolver;
import com.shimeta.model.Agent;
import com.shimeta.model.AgentStatus;
import com.shimeta.model.ApiResponse;
import com.shimeta.model.ChatRequest;
import com.shimeta.model.ChatResponse;
import com.shimeta.model.GuardrailPolicy;
import com.shimeta.model.OpenApiChatRequest;
import com.shimeta.model.OpenApiKey;
import com.shimeta.repository.AgentRepository;
import com.shimeta.repository.GuardrailPolicyRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.security.OpenApiContext;
import com.shimeta.security.OpenApiScopes;
import com.shimeta.security.guardrail.ContentGuardService;
import com.shimeta.security.guardrail.InputGuardResult;
import com.shimeta.security.guardrail.OutputGuardResult;
import com.shimeta.security.guardrail.StreamingSlidingWindowGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class OpenChatService {

    private static final Logger log = LoggerFactory.getLogger(OpenChatService.class);

    private final AgentRepository agentRepository;
    private final AiChatService aiChatService;
    private final ResourceAuthorizationService authorizationService;
    private final GuardrailPolicyRepository policyRepository;
    private final AuditRecorder auditRecorder;
    private final UsageRecorder usageRecorder;
    private final ObjectMapper objectMapper;
    private final ContentGuardService contentGuardService;
    private final AgentConversationService conversationService;
    private final PublicUrlResolver publicUrlResolver;

    public OpenChatService(AgentRepository agentRepository,
                           AiChatService aiChatService,
                           ResourceAuthorizationService authorizationService,
                           GuardrailPolicyRepository policyRepository,
                           AuditRecorder auditRecorder,
                           UsageRecorder usageRecorder,
                           ObjectMapper objectMapper,
                           ContentGuardService contentGuardService,
                           AgentConversationService conversationService,
                           PublicUrlResolver publicUrlResolver) {
        this.agentRepository = agentRepository;
        this.aiChatService = aiChatService;
        this.authorizationService = authorizationService;
        this.policyRepository = policyRepository;
        this.auditRecorder = auditRecorder;
        this.usageRecorder = usageRecorder;
        this.objectMapper = objectMapper;
        this.contentGuardService = contentGuardService;
        this.conversationService = conversationService;
        this.publicUrlResolver = publicUrlResolver;
    }

    public Object chat(OpenApiChatRequest request, HttpServletRequest httpRequest) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || ctx.getKey() == null) {
            return error(HttpStatus.UNAUTHORIZED, "key_invalid", "未提供有效的开放凭证");
        }
        if (!ctx.hasScope(OpenApiScopes.CHAT)) {
            auditRecorder.record("chat.denied", "AGENT", request.getAgentId(), "DENIED", "scope_missing", "MEDIUM", null);
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证没有 chat 权限");
        }

        Agent agent = resolveAgent(request, ctx);
        if (agent == null) {
            return error(HttpStatus.FORBIDDEN, "agent_out_of_scope", "未指定智能体，或该智能体不在凭证范围内");
        }
        if (agent.getStatus() != AgentStatus.RUNNING) {
            return error(HttpStatus.FORBIDDEN, "agent_not_running", "智能体未处于运行状态");
        }
        CurrentActor actor = ctx.asActor();
        if (!authorizationService.canRunAgent(actor, agent)) {
            auditRecorder.record("chat.denied", "AGENT", agent.getId(), "DENIED", "agent_out_of_scope", "MEDIUM", null);
            return error(HttpStatus.FORBIDDEN, "agent_out_of_scope", "无权调用该智能体");
        }
        try {
            authorizationService.checkAgentKnowledgeBaseDependencies(agent);
        } catch (IllegalStateException e) {
            auditRecorder.record("chat.denied", "AGENT", agent.getId(), "DENIED", "dependency_invalid", "HIGH", e.getMessage());
            return error(HttpStatus.FORBIDDEN, "dependency_invalid", e.getMessage());
        }

        GuardrailPolicy policy = policyRepository.findById(ctx.getOwner().getId())
                .or(() -> policyRepository.findById("GLOBAL"))
                .orElse(null);
        if (policy != null && Boolean.TRUE.equals(policy.getKillSwitch())) {
            auditRecorder.record("chat.denied", "AGENT", agent.getId(), "DENIED", "kill_switch", "HIGH", null);
            return error(HttpStatus.FORBIDDEN, "kill_switch", "该开发者的开放调用已被紧急停用");
        }

        InputGuardResult inputCheck = contentGuardService.inspectInput(policy, request.getMessage());
        if (inputCheck.isBlocked()) {
            auditRecorder.record("chat.denied", "AGENT", agent.getId(), "DENIED", inputCheck.getReasonCode(), "HIGH", inputCheck.getMatchedTerm());
            return error(HttpStatus.UNPROCESSABLE_ENTITY, inputCheck.getReasonCode(), inputCheck.getMessage());
        }
        if (inputCheck.getAuditType() != null) {
            auditRecorder.record("chat.warning", "AGENT", agent.getId(), "LOGGED", inputCheck.getAuditType(), "MEDIUM", inputCheck.getMatchedTerm());
        }

        ChatRequest chatReq = new ChatRequest();
        chatReq.setAgentId(agent.getId());
        chatReq.setMessage(inputCheck.getProcessedText().trim());
        chatReq.setConversationId(request.getConversationId());
        String endUser = request.getEndUser() != null && !request.getEndUser().isBlank()
                ? request.getEndUser().trim()
                : (request.getUser() != null && !request.getUser().isBlank() ? request.getUser().trim() : "API-Caller");
        chatReq.setAccount(endUser);
        // 上下文记忆：同一调用方带回 conversation_id 时，从已保存的对话里取最近 N 轮。
        // 轮数取智能体设置，且不超过该开发者护栏策略的「最大历史轮数」
        int memoryTurns = agent.resolveMemoryTurns();
        if (policy != null && policy.getMaxHistoryTurns() != null) {
            memoryTurns = Math.min(memoryTurns, Math.max(0, policy.getMaxHistoryTurns()));
        }
        chatReq.setHistory(conversationService.recentHistory(agent.getId(), request.getConversationId(), endUser, memoryTurns));
        chatReq.setPrompt(agent.getSystemPrompt());
        chatReq.setEnabledTools(extractEnabledTools(agent.getToolsConfig()));

        // FAQ 附图返回完整网址，广告机等终端拿到后可以直接显示
        String publicBaseUrl = publicUrlResolver.baseUrl(httpRequest);
        boolean streaming = "streaming".equalsIgnoreCase(request.getResponseMode());
        if (streaming) {
            return stream(chatReq, agent, policy, publicBaseUrl);
        }
        try {
            ChatResponse chatResp = aiChatService.chat(chatReq);
            String rawReply = chatResp.getReply();
            OutputGuardResult outputCheck = contentGuardService.inspectOutput(policy, rawReply);
            if (outputCheck.isBlocked()) {
                auditRecorder.record("chat.output_blocked", "AGENT", agent.getId(), "BLOCKED", outputCheck.getReasonCode(), "HIGH", outputCheck.getMatchedTerm());
                chatResp.setReply("【内容安全告警】智能体回复内容未通过合规安全审核，已被系统拦截。");
            } else {
                chatResp.setReply(outputCheck.getProcessedText());
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("conversation_id", chatResp.getConversationId());
            data.put("message_id", "msg-" + UUID.randomUUID().toString().substring(0, 8));
            data.put("reply", chatResp.getReply());
            data.put("images", outputCheck.isBlocked() ? List.of()
                    : PublicUrlResolver.absolute(chatResp.getImages(), publicBaseUrl));
            data.put("model", chatResp.getModel() != null ? chatResp.getModel() : agent.getModelName());
            data.put("tool_called", chatResp.getToolCalled());
            data.put("tokens_used", chatResp.getTokensUsed());
            data.put("latency_ms", chatResp.getLatencyMs());
            data.put("created_at", System.currentTimeMillis() / 1000);
            data.put("request_id", ctx.getRequestId());
            if (outputCheck.isBlocked()) {
                data.put("guardrail", "blocked");
            }
            auditRecorder.record("chat.invoke", "AGENT", agent.getId(), "SUCCESS", null, "LOW", null);
            usageRecorder.record("chat", agent.getId(), chatResp.getConversationId(), 200, null,
                    chatResp.getLatencyMs() != null ? chatResp.getLatencyMs().intValue() : 0,
                    tokens(chatResp.getPromptTokens()), tokens(chatResp.getCompletionTokens()),
                    chatResp.getModel() != null ? chatResp.getModel() : agent.getModelName());
            return ResponseEntity.ok(ApiResponse.ok("success", data));
        } catch (IllegalStateException e) {
            return error(HttpStatus.FORBIDDEN, "dependency_invalid", e.getMessage());
        } catch (Exception e) {
            log.error("Open chat error: {}", e.getMessage(), e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "upstream_error", "模型调用失败");
        }
    }

    public ResponseEntity<ApiResponse<Map<String, Object>>> stopUnsupported(String taskId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(errorBody("not_supported", "停止生成尚未接入上游取消，taskId=" + taskId));
    }

    /**
     * 流式输出：模型边生成边推送（经引用角标清理与输出护栏的滑动窗口检查）。
     * 通道不支持流式或走了兜底回复时，拿到完整回复后再一次性输出。
     */
    private Object stream(ChatRequest chatReq, Agent agent, GuardrailPolicy policy, String publicBaseUrl) {
        SseEmitter emitter = new SseEmitter(180_000L);
        // 流式在后台线程执行：带上本次开放调用的上下文，用量、配额与审计才能记到对应的凭证和终端上
        OpenApiContext ctx = OpenApiContext.get();
        // 新会话预先分配 ID，第一段内容里就能带上会话 ID
        String newConversationId = AgentConversationService.newConversationId();
        chatReq.setNewConversationId(newConversationId);
        String convId = conversationService.belongsToAgent(agent.getId(), chatReq.getConversationId())
                ? chatReq.getConversationId().trim() : newConversationId;
        SseWriter writer = new SseWriter(emitter, contentGuardService.createStreamingGuard(policy), agent,
                convId, "msg-" + UUID.randomUUID().toString().substring(0, 8), publicBaseUrl);
        emitter.onCompletion(writer::markClosed);
        emitter.onTimeout(writer::markClosed);
        emitter.onError(e -> writer.markClosed());

        CompletableFuture.runAsync(() -> {
            if (ctx != null) {
                OpenApiContext.set(ctx);
                CurrentActor.set(ctx.asActor());
            }
            StreamingCitationStripper stripper = new StreamingCitationStripper();
            try {
                ChatResponse chatResp = aiChatService.chat(chatReq, new AiChatService.StreamSink() {
                    @Override
                    public void onDelta(String text) {
                        writer.streamed = true;
                        writer.write(stripper.push(text));
                    }

                    @Override
                    public boolean cancelled() {
                        return writer.blocked || writer.closed;
                    }
                });
                writer.write(writer.streamed ? stripper.flush() : chatResp.getReply());
                writer.finish(chatResp);
                if (!writer.blocked) {
                    auditRecorder.record("chat.invoke", "AGENT", agent.getId(), "SUCCESS", null, "LOW", null);
                }
                usageRecorder.record("chat", agent.getId(), chatResp.getConversationId(), writer.blocked ? 422 : 200, null,
                        chatResp.getLatencyMs() != null ? chatResp.getLatencyMs().intValue() : 0,
                        tokens(chatResp.getPromptTokens()), tokens(chatResp.getCompletionTokens()),
                        chatResp.getModel() != null ? chatResp.getModel() : agent.getModelName());
            } catch (Exception e) {
                log.error("Open SSE error: {}", e.getMessage(), e);
                writer.fail();
            } finally {
                OpenApiContext.clear();
                CurrentActor.clear();
            }
        });
        return emitter;
    }

    private static int tokens(Integer value) {
        return value != null ? value : 0;
    }

    /** 单次流式响应的输出状态：经输出护栏后写出 message 事件，命中拦截时发送 guardrail 事件并停止后续输出 */
    private class SseWriter {
        private final SseEmitter emitter;
        private final StreamingSlidingWindowGuard guard;
        private final Agent agent;
        private final String convId;
        private final String msgId;
        private final String publicBaseUrl;
        private final long createdAt = System.currentTimeMillis() / 1000;
        volatile boolean streamed;
        volatile boolean blocked;
        volatile boolean closed;

        SseWriter(SseEmitter emitter, StreamingSlidingWindowGuard guard, Agent agent, String convId, String msgId,
                  String publicBaseUrl) {
            this.emitter = emitter;
            this.guard = guard;
            this.agent = agent;
            this.convId = convId;
            this.msgId = msgId;
            this.publicBaseUrl = publicBaseUrl;
        }

        void markClosed() {
            closed = true;
        }

        synchronized void write(String text) {
            if (blocked || closed || text == null || text.isEmpty()) {
                return;
            }
            List<String> safeChunks = guard.processChunk(text);
            if (guard.isViolationDetected()) {
                block();
                return;
            }
            safeChunks.forEach(this::sendMessage);
        }

        synchronized void finish(ChatResponse chatResp) {
            if (!blocked && !closed) {
                String remaining = guard.flush();
                if (guard.isViolationDetected()) {
                    block();
                } else {
                    sendMessage(remaining);
                }
            }
            Map<String, Object> endPayload = new LinkedHashMap<>();
            endPayload.put("event", "message_end");
            endPayload.put("conversation_id", chatResp.getConversationId() != null ? chatResp.getConversationId() : convId);
            endPayload.put("message_id", msgId);
            endPayload.put("images", blocked ? List.of() : PublicUrlResolver.absolute(chatResp.getImages(), publicBaseUrl));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("model", chatResp.getModel() != null ? chatResp.getModel() : agent.getModelName());
            metadata.put("tokens_used", chatResp.getTokensUsed() != null ? chatResp.getTokensUsed() : 0);
            metadata.put("latency_ms", chatResp.getLatencyMs() != null ? chatResp.getLatencyMs() : 0);
            metadata.put("tool_called", chatResp.getToolCalled());
            if (blocked) {
                metadata.put("guardrail", "blocked");
            }
            endPayload.put("metadata", metadata);
            send("message_end", endPayload);
            emitter.complete();
        }

        synchronized void fail() {
            if (!closed) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("{\"event\":\"error\",\"message\":\"模型调用失败\"}"));
                } catch (Exception ignored) {
                    // 调用方已断开
                }
            }
            emitter.complete();
        }

        private void block() {
            blocked = true;
            Map<String, Object> guardPayload = new LinkedHashMap<>();
            guardPayload.put("event", "guardrail");
            guardPayload.put("action", "blocked");
            guardPayload.put("reason", "output_violation");
            guardPayload.put("message", "输出命中敏感内容，流式传输已即时中断");
            send("guardrail", guardPayload);
            auditRecorder.record("chat.output_blocked", "AGENT", agent.getId(), "BLOCKED", "streaming_violation", "HIGH", guard.getViolatedTerm());
        }

        private void sendMessage(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            Map<String, Object> chunkPayload = new LinkedHashMap<>();
            chunkPayload.put("event", "message");
            chunkPayload.put("conversation_id", convId);
            chunkPayload.put("message_id", msgId);
            chunkPayload.put("answer", text);
            chunkPayload.put("created_at", createdAt);
            send("message", chunkPayload);
        }

        private void send(String event, Map<String, Object> payload) {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(payload)));
            } catch (Exception e) {
                // 调用方断开连接：停止推送，并通过 cancelled() 让模型停止生成
                closed = true;
            }
        }
    }

    private Agent resolveAgent(OpenApiChatRequest request, OpenApiContext ctx) {
        OpenApiKey key = ctx.getKey();
        List<String> scope = key.getAgentScope();
        String agentId = request.getAgentId();
        if ((agentId == null || agentId.isBlank()) && scope != null && scope.size() == 1) {
            agentId = scope.get(0);
        }
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        final String finalAgentId = agentId;
        Agent agent = agentRepository.findById(finalAgentId)
                .or(() -> agentRepository.findByCode(finalAgentId))
                .orElse(null);
        if (agent == null) {
            return null;
        }
        if (scope != null && !scope.isEmpty()
                && !scope.contains(agent.getId())
                && (agent.getCode() == null || !scope.contains(agent.getCode()))) {
            return null;
        }
        return agent;
    }

    private List<String> extractEnabledTools(String toolsConfig) {
        if (toolsConfig == null || toolsConfig.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(toolsConfig);
            if (root.isArray()) {
                List<String> list = new ArrayList<>();
                for (JsonNode item : root) {
                    if (item.path("enabled").asBoolean(true)) {
                        String name = item.path("name").asText();
                        if (!name.isBlank()) {
                            list.add(name);
                        }
                    }
                }
                return list;
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(errorBody(code, message));
    }

    private ApiResponse<Map<String, Object>> errorBody(String code, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx != null) {
            data.put("request_id", ctx.getRequestId());
        }
        return ApiResponse.error(message, data);
    }
}
