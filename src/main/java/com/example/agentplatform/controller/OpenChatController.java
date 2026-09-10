package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.OpenApiChatRequest;
import com.example.agentplatform.service.OpenChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/open/v1")
public class OpenChatController {

    private final OpenChatService openChatService;

    public OpenChatController(OpenChatService openChatService) {
        this.openChatService = openChatService;
    }

    @PostMapping(value = "/chat-messages", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object chat(@RequestBody OpenApiChatRequest request, HttpServletRequest httpRequest) {
        return openChatService.chat(request, httpRequest);
    }

    @PostMapping("/chat-messages/{taskId}/stop")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stop(@PathVariable String taskId) {
        return openChatService.stopUnsupported(taskId);
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "version", "open-v1",
                "status", "RUNNING",
                "timestamp", System.currentTimeMillis()
        )));
    }
}
