package com.example.agentplatform.service;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.ChatRequest;
import com.example.agentplatform.model.ChatResponse;
import com.example.agentplatform.model.GuardrailPolicy;
import com.example.agentplatform.model.OpenApiChatRequest;
import com.example.agentplatform.model.OpenApiKey;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.repository.GuardrailPolicyRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.security.OpenApiContext;
import com.example.agentplatform.security.OpenApiScopes;
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

    public OpenChatService(AgentRepository agentRepository,
                           AiChatService aiChatService,
                           ResourceAuthorizationService authorizationService,
                           GuardrailPolicyRepository policyRepository,
                           AuditRecorder auditRecorder,
                           UsageRecorder usageRecorder,
                           ObjectMapper objectMapper) {
        this.agentRepository = agentRepository;
        this.aiChatService = aiChatService;
        this.authorizationService = authorizationService;
        this.policyRepository = policyRepository;
        this.auditRecorder = auditRecorder;
        this.usageRecorder = usageRecorder;
        this.objectMapper = objectMapper;
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
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "input_too_long", "请求参数 message 不能为空");
        }
        if (policy != null && request.getMessage().length() > policy.getMaxInputChars()) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "input_too_long", "输入超过最大长度 " + policy.getMaxInputChars());
        }
        if (policy != null && !policy.getSensitiveWords().isEmpty() && "BLOCK".equalsIgnoreCase(policy.getSensitiveAction())) {
            String lower = request.getMessage().toLowerCase();
            for (String word : policy.getSensitiveWords()) {
                if (word != null && !word.isBlank() && lower.contains(word.toLowerCase())) {
                    auditRecorder.record("chat.denied", "AGENT", agent.getId(), "DENIED", "sensitive_content", "HIGH", word);
                    return error(HttpStatus.UNPROCESSABLE_ENTITY, "sensitive_content", "输入包含敏感内容");
                }
            }
        }

        ChatRequest chatReq = new ChatRequest();
        chatReq.setAgentId(agent.getId());
        chatReq.setMessage(request.getMessage().trim());
        chatReq.setConversationId(request.getConversationId());
        String endUser = request.getEndUser() != null && !request.getEndUser().isBlank()
                ? request.getEndUser().trim()
                : (request.getUser() != null && !request.getUser().isBlank() ? request.getUser().trim() : "API-Caller");
        chatReq.setAccount(endUser);
        chatReq.setPrompt(agent.getSystemPrompt());
        chatReq.setEnabledTools(extractEnabledTools(agent.getToolsConfig()));

        boolean streaming = "streaming".equalsIgnoreCase(request.getResponseMode());
        if (streaming) {
            return stream(chatReq, agent);
        }
        try {
            ChatResponse chatResp = aiChatService.chat(chatReq);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("conversation_id", chatResp.getConversationId());
            data.put("message_id", "msg-" + UUID.randomUUID().toString().substring(0, 8));
            data.put("reply", chatResp.getReply());
            data.put("model", chatResp.getModel() != null ? chatResp.getModel() : agent.getModelName());
            data.put("tool_called", chatResp.getToolCalled());
            data.put("tokens_used", chatResp.getTokensUsed());
            data.put("latency_ms", chatResp.getLatencyMs());
            data.put("created_at", System.currentTimeMillis() / 1000);
            data.put("request_id", ctx.getRequestId());
            auditRecorder.record("chat.invoke", "AGENT", agent.getId(), "SUCCESS", null, "LOW", null);
            usageRecorder.record("chat", agent.getId(), chatResp.getConversationId(), 200, null,
                    chatResp.getLatencyMs() != null ? chatResp.getLatencyMs().intValue() : 0,
                    chatResp.getTokensUsed() != null ? chatResp.getTokensUsed() : 0,
                    0, chatResp.getModel() != null ? chatResp.getModel() : agent.getModelName());
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

    private Object stream(ChatRequest chatReq, Agent agent) {
        SseEmitter emitter = new SseEmitter(180_000L);
        CompletableFuture.runAsync(() -> {
            try {
                ChatResponse chatResp = aiChatService.chat(chatReq);
                String reply = chatResp.getReply() != null ? chatResp.getReply() : "";
                String convId = chatResp.getConversationId() != null ? chatResp.getConversationId() : "conv-" + UUID.randomUUID().toString().substring(0, 8);
                String msgId = "msg-" + UUID.randomUUID().toString().substring(0, 8);
                long createdAt = System.currentTimeMillis() / 1000;
                int chunkSize = 3;
                for (int i = 0; i < reply.length(); i += chunkSize) {
                    int end = Math.min(reply.length(), i + chunkSize);
                    Map<String, Object> chunkPayload = new LinkedHashMap<>();
                    chunkPayload.put("event", "message");
                    chunkPayload.put("conversation_id", convId);
                    chunkPayload.put("message_id", msgId);
                    chunkPayload.put("answer", reply.substring(i, end));
                    chunkPayload.put("created_at", createdAt);
                    emitter.send(SseEmitter.event().name("message").data(objectMapper.writeValueAsString(chunkPayload)));
                    Thread.sleep(25);
                }
                Map<String, Object> endPayload = new LinkedHashMap<>();
                endPayload.put("event", "message_end");
                endPayload.put("conversation_id", convId);
                endPayload.put("message_id", msgId);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("model", chatResp.getModel() != null ? chatResp.getModel() : agent.getModelName());
                metadata.put("tokens_used", chatResp.getTokensUsed() != null ? chatResp.getTokensUsed() : 0);
                metadata.put("latency_ms", chatResp.getLatencyMs() != null ? chatResp.getLatencyMs() : 0);
                metadata.put("tool_called", chatResp.getToolCalled());
                endPayload.put("metadata", metadata);
                emitter.send(SseEmitter.event().name("message_end").data(objectMapper.writeValueAsString(endPayload)));
                emitter.complete();
                auditRecorder.record("chat.invoke", "AGENT", agent.getId(), "SUCCESS", null, "LOW", null);
                usageRecorder.record("chat", agent.getId(), convId, 200, null,
                        chatResp.getLatencyMs() != null ? chatResp.getLatencyMs().intValue() : 0,
                        chatResp.getTokensUsed() != null ? chatResp.getTokensUsed() : 0,
                        0, chatResp.getModel() != null ? chatResp.getModel() : agent.getModelName());
            } catch (Exception e) {
                log.error("Open SSE error: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event().name("error").data("{\"event\":\"error\",\"message\":\"模型调用失败\"}"));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
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
        if (scope != null && !scope.isEmpty() && !scope.contains(agentId)) {
            return null;
        }
        return agentRepository.findById(agentId).orElse(null);
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
