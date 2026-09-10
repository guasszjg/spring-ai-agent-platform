package com.example.agentplatform.controller;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.OpenAgentDto;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.repository.KnowledgeBaseRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.security.OpenApiContext;
import com.example.agentplatform.security.OpenApiScopes;
import com.example.agentplatform.service.AgentService;
import com.example.agentplatform.service.AuditRecorder;
import com.example.agentplatform.service.ResourceAuthorizationService;
import com.example.agentplatform.service.UsageRecorder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/open/v1/agents")
public class OpenAgentController {

    private final AgentService agentService;
    private final AgentRepository agentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ResourceAuthorizationService authorizationService;
    private final AuditRecorder auditRecorder;
    private final UsageRecorder usageRecorder;

    public OpenAgentController(AgentService agentService,
                               AgentRepository agentRepository,
                               KnowledgeBaseRepository knowledgeBaseRepository,
                               ResourceAuthorizationService authorizationService,
                               AuditRecorder auditRecorder,
                               UsageRecorder usageRecorder) {
        this.agentService = agentService;
        this.agentRepository = agentRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.authorizationService = authorizationService;
        this.auditRecorder = auditRecorder;
        this.usageRecorder = usageRecorder;
    }

    @GetMapping
    public ResponseEntity<?> listAgents() {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.AGENTS_READ)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 agents:read 权限");
        }
        CurrentActor actor = ctx.asActor();
        List<Agent> allAccessible = agentService.searchAgents(null, null, null, null, null, actor, 1, 1000).getRecords();
        List<String> keyScope = ctx.getKey().getAgentScope();

        List<OpenAgentDto> dtos = allAccessible.stream()
                .filter(a -> keyScope == null || keyScope.isEmpty() || keyScope.contains(a.getId()))
                .map(OpenAgentDto::fromEntity)
                .collect(Collectors.toList());

        usageRecorder.record("agents.list", null, null, 200, null, 10, 0, 0, null);
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getAgent(@PathVariable String id) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.AGENTS_READ)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 agents:read 权限");
        }
        List<String> keyScope = ctx.getKey().getAgentScope();
        if (keyScope != null && !keyScope.isEmpty() && !keyScope.contains(id)) {
            return error(HttpStatus.FORBIDDEN, "agent_out_of_scope", "该智能体不在当前凭证范围内");
        }
        Agent agent = agentRepository.findById(id).orElse(null);
        if (agent == null) {
            return error(HttpStatus.NOT_FOUND, "agent_not_found", "智能体不存在");
        }
        if (!authorizationService.canViewAgent(ctx.asActor(), agent)) {
            return error(HttpStatus.FORBIDDEN, "agent_out_of_scope", "无权访问该智能体");
        }
        usageRecorder.record("agents.get", id, null, 200, null, 5, 0, 0, null);
        return ResponseEntity.ok(ApiResponse.ok(OpenAgentDto.fromEntity(agent)));
    }

    @PostMapping
    public ResponseEntity<?> createAgent(@RequestBody Agent request) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.AGENTS_WRITE)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 agents:write 权限");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_parameter", "智能体名称不能为空");
        }
        try {
            Agent created = agentService.create(request, ctx.asActor());
            auditRecorder.record("agent.create", "AGENT", created.getId(), "SUCCESS", null, "LOW", created.getName());
            usageRecorder.record("agents.create", created.getId(), null, 200, null, 15, 0, 0, null);
            return ResponseEntity.ok(ApiResponse.ok("创建成功", OpenAgentDto.fromEntity(created)));
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_parameter", e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateAgent(@PathVariable String id, @RequestBody Agent request) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.AGENTS_WRITE)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 agents:write 权限");
        }
        Agent existing = agentRepository.findById(id).orElse(null);
        if (existing == null) {
            return error(HttpStatus.NOT_FOUND, "agent_not_found", "智能体不存在");
        }
        if (!authorizationService.canManageAgent(ctx.asActor(), existing)) {
            return error(HttpStatus.FORBIDDEN, "agent_out_of_scope", "无权修改该智能体");
        }
        try {
            Agent updated = agentService.update(id, request, ctx.asActor());
            auditRecorder.record("agent.update", "AGENT", updated.getId(), "SUCCESS", null, "LOW", updated.getName());
            usageRecorder.record("agents.update", updated.getId(), null, 200, null, 15, 0, 0, null);
            return ResponseEntity.ok(ApiResponse.ok("更新成功", OpenAgentDto.fromEntity(updated)));
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_parameter", e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteAgent(@PathVariable String id) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.AGENTS_WRITE)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 agents:write 权限");
        }
        Agent existing = agentRepository.findById(id).orElse(null);
        if (existing == null) {
            return error(HttpStatus.NOT_FOUND, "agent_not_found", "智能体不存在");
        }
        if (!authorizationService.canManageAgent(ctx.asActor(), existing)) {
            return error(HttpStatus.FORBIDDEN, "agent_out_of_scope", "无权删除该智能体");
        }
        agentService.delete(id, ctx.asActor());
        auditRecorder.record("agent.delete", "AGENT", id, "SUCCESS", null, "MEDIUM", existing.getName());
        usageRecorder.record("agents.delete", id, null, 200, null, 15, 0, 0, null);
        return ResponseEntity.ok(ApiResponse.ok("删除成功", Map.of("id", id)));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<?> publishAgent(@PathVariable String id) {
        return toggleStatus(id, AgentStatus.RUNNING);
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<?> pauseAgent(@PathVariable String id) {
        return toggleStatus(id, AgentStatus.DISABLED);
    }

    private ResponseEntity<?> toggleStatus(String id, AgentStatus status) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.AGENTS_WRITE)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 agents:write 权限");
        }
        Agent existing = agentRepository.findById(id).orElse(null);
        if (existing == null) {
            return error(HttpStatus.NOT_FOUND, "agent_not_found", "智能体不存在");
        }
        if (!authorizationService.canManageAgent(ctx.asActor(), existing)) {
            return error(HttpStatus.FORBIDDEN, "agent_out_of_scope", "无权操作该智能体状态");
        }
        existing.setStatus(status);
        Agent updated = agentRepository.save(existing);
        usageRecorder.record("agents.status", id, null, 200, null, 10, 0, 0, null);
        return ResponseEntity.ok(ApiResponse.ok("状态已变更为 " + status.name(), OpenAgentDto.fromEntity(updated)));
    }

    @GetMapping("/{id}/knowledge-bases")
    public ResponseEntity<?> getBoundKnowledgeBases(@PathVariable String id) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.AGENTS_BIND_KB)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 agents:bind_kb 权限");
        }
        Agent agent = agentRepository.findById(id).orElse(null);
        if (agent == null) {
            return error(HttpStatus.NOT_FOUND, "agent_not_found", "智能体不存在");
        }
        List<String> kbIds = agent.getKnowledgeBaseIds() != null ? agent.getKnowledgeBaseIds() : List.of();
        List<KnowledgeBase> kbs = knowledgeBaseRepository.findAllById(kbIds);
        List<Map<String, Object>> result = kbs.stream().map(kb -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", kb.getId());
            map.put("name", kb.getName());
            map.put("avatar", kb.getAvatar());
            map.put("provider", kb.getProvider());
            map.put("documentCount", kb.getDocumentCount());
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PutMapping("/{id}/knowledge-bases")
    public ResponseEntity<?> updateBoundKnowledgeBases(@PathVariable String id, @RequestBody Map<String, Object> body) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.AGENTS_BIND_KB)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 agents:bind_kb 权限");
        }
        Agent agent = agentRepository.findById(id).orElse(null);
        if (agent == null) {
            return error(HttpStatus.NOT_FOUND, "agent_not_found", "智能体不存在");
        }
        if (!authorizationService.canManageAgent(ctx.asActor(), agent)) {
            return error(HttpStatus.FORBIDDEN, "agent_out_of_scope", "无权为该智能体绑定知识库");
        }
        @SuppressWarnings("unchecked")
        List<String> newIds = (List<String>) body.get("knowledge_base_ids");
        if (newIds == null) {
            newIds = new ArrayList<>();
        }
        agent.setKnowledgeBaseIds(newIds);
        try {
            authorizationService.checkAgentKnowledgeBaseDependencies(agent);
        } catch (IllegalStateException e) {
            return error(HttpStatus.FORBIDDEN, "dependency_invalid", e.getMessage());
        }
        agentRepository.save(agent);
        auditRecorder.record("agent.bind_kb", "AGENT", id, "SUCCESS", null, "LOW", "绑定知识库数量: " + newIds.size());
        usageRecorder.record("agents.bind_kb", id, null, 200, null, 15, 0, 0, null);
        return ResponseEntity.ok(ApiResponse.ok("知识库绑定更新成功", Map.of("knowledge_base_ids", newIds)));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> error(HttpStatus status, String code, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx != null) {
            data.put("request_id", ctx.getRequestId());
        }
        return ResponseEntity.status(status).body(ApiResponse.error(message, data));
    }
}
