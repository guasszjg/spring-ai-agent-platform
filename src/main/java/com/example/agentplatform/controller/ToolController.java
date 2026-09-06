package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.tool.AgentToolRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final AgentToolRegistry toolRegistry;

    public ToolController(AgentToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @PostMapping("/bocha/test")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testBochaConnection(@RequestBody(required = false) Map<String, String> body) {
        String apiKey = body != null ? body.get("apiKey") : null;
        Map<String, Object> result = toolRegistry.testBochaConnection(apiKey);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        String msg = (String) result.getOrDefault("message", success ? "测试成功" : "测试失败");
        if (success) {
            return ResponseEntity.ok(ApiResponse.ok(msg, result));
        } else {
            return ResponseEntity.ok(ApiResponse.error(msg, result));
        }
    }
}