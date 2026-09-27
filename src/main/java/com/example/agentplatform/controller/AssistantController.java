package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.ChatRequest;
import com.example.agentplatform.model.ChatResponse;
import com.example.agentplatform.service.AiChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台 AI 助手：控制台右侧的全局助手，不绑定智能体，对话走模型网关的默认路由。
 * 路径位于 /api/** 下，由会话拦截器保证仅登录用户可用。
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private static final int MAX_MESSAGE_LENGTH = 4000;

    private final AiChatService aiChatService;

    public AssistantController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        String message = request != null && request.getMessage() != null ? request.getMessage().trim() : "";
        if (message.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("请输入要咨询的问题"));
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return ResponseEntity.badRequest().body(ApiResponse.error("问题过长，请控制在 " + MAX_MESSAGE_LENGTH + " 字以内"));
        }
        ChatResponse response = aiChatService.assistantChat(message, request.getHistory());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
