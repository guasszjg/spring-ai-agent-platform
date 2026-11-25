package com.shimeta.controller;

import com.shimeta.model.ApiResponse;
import com.shimeta.service.share.ShareAccessException;
import com.shimeta.service.share.ShareAccessService;
import com.shimeta.service.share.ShareChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分享链接的访客接口（免登录）。不经过平台的登录拦截器；访客令牌放在 X-Share-Token 请求头，
 * 不用 Cookie，所以不需要 CSRF 校验。所有接口都会重新校验链接
 */
@RestController
@RequestMapping("/share/v1/links/{code}")
public class ShareVisitorController {

    private static final Logger log = LoggerFactory.getLogger(ShareVisitorController.class);
    static final String TOKEN_HEADER = "X-Share-Token";

    private final ShareAccessService accessService;
    private final ShareChatService chatService;

    public ShareVisitorController(ShareAccessService accessService, ShareChatService chatService) {
        this.accessService = accessService;
        this.chatService = chatService;
    }

    public record SessionRequest(String visitorId, String accessCode) {
    }

    public record MessageRequest(String message, String conversationId) {
    }

    /** 打开链接：访问方式和标题 */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> entry(@PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.entry(code)));
    }

    /** 校验访问码（公开链接直接通过），换访客令牌 */
    @PostMapping("/session")
    public ResponseEntity<ApiResponse<Map<String, Object>>> session(@PathVariable String code,
                                                                    @RequestBody(required = false) SessionRequest body,
                                                                    HttpServletRequest request) {
        ShareAccessService.IssuedToken issued = accessService.startSession(code,
                body != null ? body.visitorId() : null, body != null ? body.accessCode() : null, clientIp(request));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("visitorId", issued.visitorId());
        data.put("token", issued.token());
        data.put("expiresAt", issued.expiresAt());
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    @GetMapping("/config")
    public ResponseEntity<ApiResponse<Map<String, Object>>> config(@PathVariable String code,
                                                                   @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.config(code, token)));
    }

    @GetMapping("/conversations")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> conversations(@PathVariable String code,
                                                                                @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.conversations(code, token)));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> messages(@PathVariable String code,
                                                                           @PathVariable String conversationId,
                                                                           @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.messages(code, token, conversationId)));
    }

    /** 发消息，SSE 流式返回（事件：message、message_end、guardrail、error）。停止生成时断开连接即可 */
    @PostMapping(value = "/messages", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public Object send(@PathVariable String code,
                       @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                       @RequestBody(required = false) MessageRequest body,
                       HttpServletRequest request) {
        return chatService.send(code, token, body != null ? body.message() : null,
                body != null ? body.conversationId() : null, request);
    }

    @ExceptionHandler(ShareAccessException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> rejected(ShareAccessException e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", e.getCode());
        if (e.getRetryAfterSeconds() > 0) {
            data.put("retryAfter", e.getRetryAfterSeconds());
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.getStatus()).contentType(MediaType.APPLICATION_JSON);
        if (e.getRetryAfterSeconds() > 0) {
            builder.header("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
        }
        return builder.body(ApiResponse.error(e.getMessage(), data));
    }

    /** 其他异常不把内部信息带给访客 */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> unexpected(RuntimeException e) {
        log.warn("Share visitor request failed: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error("服务暂时不可用，请稍后再试", Map.of("code", "server_error")));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
