package com.example.agentplatform.controller;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.ChatRequest;
import com.example.agentplatform.model.ChatResponse;
import com.example.agentplatform.model.OpenApiChatRequest;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.service.AiChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class AgentApiController {

    private static final Logger log = LoggerFactory.getLogger(AgentApiController.class);

    private final AgentRepository agentRepository;
    private final AiChatService aiChatService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentApiController(AgentRepository agentRepository, AiChatService aiChatService) {
        this.agentRepository = agentRepository;
        this.aiChatService = aiChatService;
    }

    /**
     * 发送对话消息 API (支持流式 streaming 与阻塞式 blocking)
     */
    @PostMapping(value = "/chat-messages", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object sendChatMessage(@RequestBody OpenApiChatRequest request, HttpServletRequest httpRequest) {
        String apiKey = extractApiKey(httpRequest);
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("API Key 未提供或格式不正确，请在请求头设置 Authorization: Bearer <API-KEY>"));
        }

        Optional<Agent> agentOpt = agentRepository.findByApiKey(apiKey);
        if (agentOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("无效的 API Key，找不到对应的智能体"));
        }

        Agent agent = agentOpt.get();
        if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("请求参数 message 不能为空"));
        }

        ChatRequest chatReq = new ChatRequest();
        chatReq.setAgentId(agent.getId());
        chatReq.setMessage(request.getMessage().trim());
        chatReq.setConversationId(request.getConversationId());
        chatReq.setAccount(request.getUser() != null && !request.getUser().isBlank() ? request.getUser().trim() : "API-Caller");
        chatReq.setPrompt(agent.getSystemPrompt());
        chatReq.setEnabledTools(extractEnabledTools(agent.getToolsConfig()));

        boolean isStreaming = "streaming".equalsIgnoreCase(request.getResponseMode());

        if (isStreaming) {
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
                        String chunk = reply.substring(i, end);
                        Map<String, Object> chunkPayload = new LinkedHashMap<>();
                        chunkPayload.put("event", "message");
                        chunkPayload.put("conversation_id", convId);
                        chunkPayload.put("message_id", msgId);
                        chunkPayload.put("answer", chunk);
                        chunkPayload.put("created_at", createdAt);

                        emitter.send(SseEmitter.event()
                                .name("message")
                                .data(objectMapper.writeValueAsString(chunkPayload)));
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

                    emitter.send(SseEmitter.event()
                            .name("message_end")
                            .data(objectMapper.writeValueAsString(endPayload)));
                    emitter.complete();
                } catch (Exception e) {
                    log.error("SSE stream error: {}", e.getMessage(), e);
                    try {
                        emitter.send(SseEmitter.event().name("error").data("{\"event\":\"error\",\"message\":\"" + e.getMessage() + "\"}"));
                    } catch (Exception ignored) {}
                    emitter.completeWithError(e);
                }
            });
            return emitter;
        } else {
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
                return ResponseEntity.ok(ApiResponse.ok("success", data));
            } catch (IllegalStateException e) {
                log.warn("Blocking chat rejected: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error(e.getMessage()));
            } catch (Exception e) {
                log.error("Blocking chat error: {}", e.getMessage(), e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.error("模型调用失败: " + e.getMessage()));
            }
        }
    }

    /**
     * 服务状态与心跳探针
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> apiStatus() {
        return ResponseEntity.ok(ApiResponse.ok("API 服务运行正常", Map.of(
                "version", "v1.0",
                "status", "RUNNING",
                "timestamp", System.currentTimeMillis()
        )));
    }

    /**
     * 占位扩展接口：停止正在运行的响应任务 (保留后续扩展)
     */
    @PostMapping("/chat-messages/{taskId}/stop")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stopTask(@PathVariable String taskId) {
        return ResponseEntity.ok(ApiResponse.ok("响应已停止", Map.of("taskId", taskId, "status", "STOPPED")));
    }

    private String extractApiKey(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        String customKey = request.getHeader("X-API-Key");
        if (customKey != null && !customKey.isBlank()) {
            return customKey.trim();
        }
        return null;
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
        } catch (Exception ignored) {}
        return List.of();
    }
}
