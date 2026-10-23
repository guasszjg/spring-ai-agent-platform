package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.AgentPromptVersionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 智能体系统提示词版本：列表与详情（只读）。版本由修改提示词时自动生成；回滚即把旧版本内容再发布一次，
 * 走 {@code PUT /api/agents/{id}}（promptChangeSource=ROLLBACK）。
 */
@RestController
@RequestMapping("/api/agents/{agentId}/prompt-versions")
public class AgentPromptVersionController {

    private final AgentPromptVersionService versionService;

    public AgentPromptVersionController(AgentPromptVersionService versionService) {
        this.versionService = versionService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(@PathVariable String agentId) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(versionService.list(agentId, CurrentActor.get())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{versionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String agentId, @PathVariable String versionId) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(versionService.get(agentId, versionId, CurrentActor.get())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }
}
