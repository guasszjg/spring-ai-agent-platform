package com.example.agentplatform.controller;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.PageResult;
import com.example.agentplatform.service.AgentService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping
    public ApiResponse<PageResult<Agent>> listAgents(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) AgentStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "6") int size) {
        PageResult<Agent> result = agentService.searchAgents(keyword, category, status, page, size);
        return ApiResponse.ok(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<Agent> getAgent(@PathVariable String id) {
        return agentService.getById(id)
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("未找到指定的智能体: " + id));
    }

    @PostMapping
    public ApiResponse<Agent> createAgent(@RequestBody Agent agent) {
        if (agent.getName() == null || agent.getName().trim().isEmpty()) {
            return ApiResponse.error("智能体名称不能为空");
        }
        if (agent.getCategory() == null || agent.getCategory().trim().isEmpty()) {
            agent.setCategory("通用智能");
        }
        Agent created = agentService.create(agent);
        return ApiResponse.ok("智能体创建成功", created);
    }

    @PutMapping("/{id}")
    public ApiResponse<Agent> updateAgent(@PathVariable String id, @RequestBody Agent agent) {
        try {
            Agent updated = agentService.update(id, agent);
            return ApiResponse.ok("智能体配置已成功更新", updated);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteAgent(@PathVariable String id) {
        boolean removed = agentService.delete(id);
        if (removed) {
            return ApiResponse.ok("智能体已成功删除", null);
        } else {
            return ApiResponse.error("删除失败，未找到该智能体");
        }
    }

    @PostMapping("/{id}/toggle-status")
    public ApiResponse<Agent> toggleStatus(@PathVariable String id) {
        try {
            Agent updated = agentService.toggleStatus(id);
            return ApiResponse.ok("智能体状态已变更", updated);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/templates")
    public ApiResponse<List<Map<String, Object>>> getTemplates() {
        return ApiResponse.ok(agentService.getPresetTemplates());
    }
}
