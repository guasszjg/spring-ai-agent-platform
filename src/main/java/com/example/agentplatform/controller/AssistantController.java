package com.example.agentplatform.controller;

import com.example.agentplatform.assistant.AssistantActionService;
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
    private final AssistantActionService actionService;
    private final RateLimiter rateLimiter;
    private final int rateLimitPerMinute;

    public AssistantController(AiChatService aiChatService,
                               AssistantService assistantService,
                               AssistantConversationService conversationService,
                               AssistantActionService actionService,
                               RateLimiter rateLimiter,
                               @Value("${app.assistant.rate-limit-per-minute:20}") int rateLimitPerMinute) {
        this.aiChatService = aiChatService;
        this.assistantService = assistantService;
        this.conversationService = conversationService;
        this.actionService = actionService;
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
        AssistantChatRequest normalized = new AssistantChatRequest(request.conversationId(), message, request.mode(),
                sanitizeContext(request.context()));
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
                .map(detail -> {
                    // detail 已校验会话属于当前用户，再附上会话中的操作卡片
                    detail.put("actions", actionService.cardsForConversation(id));
                    return ResponseEntity.ok(ApiResponse.ok(detail));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("会话不存在或已删除")));
    }

    /** 确认执行待确认操作；无论成功失败都返回最新的卡片数据。依赖的前序卡片未执行时返回 409，卡片不消耗。 */
    @PostMapping("/actions/{id}/confirm")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmAction(@PathVariable String id) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(actionService.confirm(id, CurrentActor.get())));
        } catch (AssistantActionService.DependencyNotMetException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/actions/{id}/cancel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelAction(@PathVariable String id) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(actionService.cancel(id, CurrentActor.get())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    /** 回复反馈：{"rating": "UP" | "DOWN" | null}，null 表示撤销。 */
    @PostMapping("/messages/{id}/feedback")
    public ResponseEntity<ApiResponse<Void>> feedback(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        try {
            String rating = body != null ? body.get("rating") : null;
            if (!conversationService.feedback(id, rating, CurrentActor.get())) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("消息不存在"));
            }
            return ResponseEntity.ok(ApiResponse.ok(null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteConversation(@PathVariable String id) {
        if (!conversationService.delete(id, CurrentActor.get())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("会话不存在或已删除"));
        }
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /** 页面上下文只接受已知字段并限制长度；资源是否可见由助手按当前用户权限再校验 */
    static Map<String, String> sanitizeContext(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        Map<String, String> clean = new java.util.LinkedHashMap<>();
        for (String key : List.of("page", "resourceType", "resourceId")) {
            String value = context.get(key);
            if (value != null && !value.isBlank() && value.length() <= 64 && value.matches("[A-Za-z0-9_-]+")) {
                clean.put(key, value);
            }
        }
        return clean.isEmpty() ? null : clean;
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
