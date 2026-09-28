package com.example.agentplatform.controller;

import com.example.agentplatform.assistant.AssistantChatRequest;
import com.example.agentplatform.assistant.AssistantConversationService;
import com.example.agentplatform.assistant.AssistantService;
import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.ChatRequest;
import com.example.agentplatform.model.ChatResponse;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.security.ratelimit.RateLimiter;
import com.example.agentplatform.service.AiChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 平台 AI 助手：控制台右侧的全局助手，不绑定智能体，对话走模型网关的默认路由。
 * 路径位于 /api/** 下，由会话拦截器保证仅登录用户可用，写接口需带 CSRF Token。
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private static final int MAX_MESSAGE_LENGTH = 4000;

    private final AiChatService aiChatService;
    private final AssistantService assistantService;
    private final AssistantConversationService conversationService;
    private final RateLimiter rateLimiter;
    private final int rateLimitPerMinute;

    public AssistantController(AiChatService aiChatService,
                               AssistantService assistantService,
                               AssistantConversationService conversationService,
                               RateLimiter rateLimiter,
                               @Value("${app.assistant.rate-limit-per-minute:20}") int rateLimitPerMinute) {
        this.aiChatService = aiChatService;
        this.assistantService = assistantService;
        this.conversationService = conversationService;
        this.rateLimiter = rateLimiter;
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    /** P0 非流式对话，保留作兜底。 */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        String message = request != null && request.getMessage() != null ? request.getMessage().trim() : "";
        String invalid = validateMessage(message);
        if (invalid != null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(invalid));
        }
        ChatResponse response = aiChatService.assistantChat(message, request.getHistory());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /** SSE 流式对话。校验与限流在请求线程完成，失败时返回普通 JSON 错误。 */
    @PostMapping("/chat/stream")
    public ResponseEntity<?> chatStream(@RequestBody AssistantChatRequest request) {
        CurrentActor actor = CurrentActor.get();
        if (actor == null || actor.getUserId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("登录已失效，请重新登录"));
        }
        String message = request != null && request.message() != null ? request.message().trim() : "";
        String invalid = validateMessage(message);
        if (invalid != null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(invalid));
        }
        RateLimiter.Result limit = rateLimiter.tryConsume("assistant:" + actor.getUserId(), 1, rateLimitPerMinute);
        if (!limit.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(Math.max(1, limit.resetSeconds())))
                    .body(ApiResponse.error("提问太频繁了，请 " + Math.max(1, limit.resetSeconds()) + " 秒后再试"));
        }
        AssistantChatRequest normalized = new AssistantChatRequest(request.conversationId(), message, request.mode());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(assistantService.streamChat(normalized, actor));
    }

    @GetMapping("/conversations")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> conversations() {
        return ResponseEntity.ok(ApiResponse.ok(conversationService.list(CurrentActor.get())));
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> conversation(@PathVariable String id) {
        return conversationService.detail(id, CurrentActor.get())
                .map(detail -> ResponseEntity.ok(ApiResponse.ok(detail)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("会话不存在或已删除")));
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteConversation(@PathVariable String id) {
        if (!conversationService.delete(id, CurrentActor.get())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("会话不存在或已删除"));
        }
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    private static String validateMessage(String message) {
        if (message.isEmpty()) {
            return "请输入要咨询的问题";
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return "问题过长，请控制在 " + MAX_MESSAGE_LENGTH + " 字以内";
        }
        return null;
    }
}
