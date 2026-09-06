package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.tool.AgentToolRegistry;
import com.example.agentplatform.service.AgentToolSecretService;
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
    private final AgentToolSecretService secretService;

    public ToolController(AgentToolRegistry toolRegistry, AgentToolSecretService secretService) {
        this.toolRegistry = toolRegistry;
        this.secretService = secretService;
    }

    @PostMapping("/bocha/test")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testBochaConnection(@RequestBody(required = false) Map<String, String> body) {
        String apiKey = body != null ? body.get("apiKey") : null;
        String agentId = body != null ? body.get("agentId") : null;
        if ((apiKey == null || apiKey.isBlank()) && agentId != null && !agentId.isBlank()) {
            apiKey = secretService.getBochaApiKey(agentId);
        }
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
