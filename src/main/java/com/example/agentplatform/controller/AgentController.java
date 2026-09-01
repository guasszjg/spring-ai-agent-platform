package com.example.agentplatform.controller;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.model.AgentStatusRequest;
import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.ChatRequest;
import com.example.agentplatform.model.ChatResponse;
import com.example.agentplatform.model.PageResult;
import com.example.agentplatform.service.AgentService;
import com.example.agentplatform.service.AiChatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;
    private final AiChatService aiChatService;

    public AgentController(AgentService agentService, AiChatService aiChatService) {
        this.agentService = agentService;
        this.aiChatService = aiChatService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<Agent>>> listAgents(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) AgentStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "6") int size) {
        PageResult<Agent> result = agentService.searchAgents(keyword, category, status, page, size);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Agent>> getAgent(@PathVariable String id) {
        return agentService.getById(id)
                .map(agent -> ResponseEntity.ok(ApiResponse.ok(agent)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("未找到指定的智能体: " + id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Agent>> createAgent(@RequestBody Agent agent) {
        if (agent.getName() == null || agent.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("智能体名称不能为空"));
        }
        if (agent.getCategory() == null || agent.getCategory().trim().isEmpty()) {
            agent.setCategory("通用智能");
        }
        Agent created = agentService.create(agent);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(ApiResponse.ok("智能体创建成功", created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Agent>> updateAgent(@PathVariable String id, @RequestBody Agent agent) {
        try {
            Agent updated = agentService.update(id, agent);
            return ResponseEntity.ok(ApiResponse.ok("智能体配置已成功更新", updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<Agent>> updateStatus(@PathVariable String id, @RequestBody AgentStatusRequest request) {
        try {
            Agent updated = agentService.updateStatus(id, request.getStatus());
            return ResponseEntity.ok(ApiResponse.ok("智能体状态已变更", updated));
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            HttpStatus status = message != null && message.contains("不存在") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(ApiResponse.error(message));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteAgent(@PathVariable String id) {
        boolean removed = agentService.delete(id);
        if (removed) {
            return ResponseEntity.ok(ApiResponse.ok("智能体已成功删除", null));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("删除失败，未找到该智能体"));
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@PathVariable String id, @RequestBody ChatRequest request) {
        if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("发送内容不能为空"));
        }
        request.setAgentId(id);
        try {
            ChatResponse response = aiChatService.chat(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("对话服务异常: " + e.getMessage()));
        }
    }
}
