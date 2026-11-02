package com.shimeta.controller;

import com.shimeta.model.ApiResponse;
import com.shimeta.model.OpenApiChatRequest;
import com.shimeta.service.OpenChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 早期兼容地址 /api/v1，新接入请使用 /open/v1。
 * 鉴权、凭证状态、IP 白名单、终端管控、限流配额与幂等由与 /open/v1 相同的过滤器完成（见 OpenApiFilterConfig），
 * 这里只负责转给对话服务；未通过鉴权的请求到不了这里，对话服务也会再校验一次开放调用上下文。
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class AgentApiController {

    private final OpenChatService openChatService;

    public AgentApiController(OpenChatService openChatService) {
        this.openChatService = openChatService;
    }

    @PostMapping(value = "/chat-messages", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object sendChatMessage(@RequestBody OpenApiChatRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        httpResponse.setHeader("Deprecation", "true");
        httpResponse.setHeader("Link", "</open/v1/chat-messages>; rel=\"successor-version\"");
        return openChatService.chat(request, httpRequest);
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> apiStatus() {
        return ResponseEntity.ok(ApiResponse.ok("API 服务运行正常", Map.of(
                "version", "v1.0",
                "status", "RUNNING",
                "timestamp", System.currentTimeMillis()
        )));
    }

    @PostMapping("/chat-messages/{taskId}/stop")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stopTask(@PathVariable String taskId) {
        return openChatService.stopUnsupported(taskId);
    }
}
