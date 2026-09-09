package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.AgentToolSecretService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/agents/{agentId}/tool-secrets")
public class AgentToolSecretController {

    private final AgentToolSecretService secretService;

    public AgentToolSecretController(AgentToolSecretService secretService) {
        this.secretService = secretService;
    }

    @GetMapping("/bocha")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> bochaStatus(@PathVariable String agentId) {
        try {
            boolean specific = secretService.isBochaConfiguredSpecific(agentId, CurrentActor.get());
            boolean configured = secretService.isBochaConfigured(agentId);
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "configured", configured,
                    "inherited", !specific && configured
            )));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/bocha")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> saveBocha(@PathVariable String agentId,
                                                                       @RequestBody Map<String, String> body) {
        try {
            secretService.saveBochaApiKey(agentId, body.get("apiKey"), CurrentActor.get());
            return ResponseEntity.ok(ApiResponse.ok("Bocha API Key 已加密保存", Map.of("configured", true)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/bocha")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> clearBocha(@PathVariable String agentId) {
        try {
            secretService.clearBochaApiKey(agentId, CurrentActor.get());
            return ResponseEntity.ok(ApiResponse.ok("Bocha API Key 已清除", Map.of("configured", false)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
