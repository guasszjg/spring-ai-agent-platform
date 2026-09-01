package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.service.AgentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent-templates")
public class AgentTemplateController {

    private final AgentService agentService;

    public AgentTemplateController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listTemplates() {
        return ResponseEntity.ok(ApiResponse.ok(agentService.getPresetTemplates()));
    }
}
