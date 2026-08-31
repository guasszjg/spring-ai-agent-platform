package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.ChatRequest;
import com.example.agentplatform.model.ChatResponse;
import com.example.agentplatform.service.AiChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AiChatService aiChatService;

    public ChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @PostMapping
    public ApiResponse<ChatResponse> chat(@RequestBody ChatRequest request) {
        if (request.getAgentId() == null || request.getAgentId().trim().isEmpty()) {
            return ApiResponse.error("缺少智能体 ID");
        }
        if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            return ApiResponse.error("发送内容不能为空");
        }

        try {
            ChatResponse response = aiChatService.chat(request);
            return ApiResponse.ok(response);
        } catch (Exception e) {
            return ApiResponse.error("对话服务异常: " + e.getMessage());
        }
    }
}
