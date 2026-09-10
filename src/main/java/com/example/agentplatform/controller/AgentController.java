package com.example.agentplatform.controller;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentConversation;
import com.example.agentplatform.model.AgentMonitorStats;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.model.AgentStatusRequest;
import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.ChatRequest;
import com.example.agentplatform.model.ChatResponse;
import com.example.agentplatform.model.ConversationDetail;
import com.example.agentplatform.model.LoginResponse;
import com.example.agentplatform.model.PageResult;
import com.example.agentplatform.config.SessionAuthInterceptor;
import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.AgentConversationService;
import com.example.agentplatform.service.AgentService;
import com.example.agentplatform.service.AiChatService;
import jakarta.servlet.http.HttpSession;
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
    private final AgentConversationService conversationService;
    private final com.example.agentplatform.service.ResourceAuthorizationService authService;
    private final com.example.agentplatform.service.OpenApiKeyService openApiKeyService;

    public AgentController(AgentService agentService, AiChatService aiChatService,
                           AgentConversationService conversationService,
                           com.example.agentplatform.service.ResourceAuthorizationService authService,
                           com.example.agentplatform.service.OpenApiKeyService openApiKeyService) {
        this.agentService = agentService;
        this.aiChatService = aiChatService;
        this.conversationService = conversationService;
        this.authService = authService;
        this.openApiKeyService = openApiKeyService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<Agent>>> listAgents(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) AgentStatus status,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String ownerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "6") int size) {
        PageResult<Agent> result = agentService.searchAgents(keyword, category, status, scope, ownerId, CurrentActor.get(), page, size);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Agent>> getAgent(@PathVariable String id) {
        return agentService.getById(id)
                .map(agent -> {
                    CurrentActor actor = CurrentActor.get();
                    if (!authService.canViewAgent(actor, agent)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .<ApiResponse<Agent>>body(ApiResponse.error("权限不足：无法访问该智能体"));
                    }
                    return ResponseEntity.ok(ApiResponse.ok(agent));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("未找到指定的智能体: " + id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Agent>> createAgent(@RequestBody Agent agent) {
        CurrentActor actor = CurrentActor.get();
        if (actor != null && actor.getRole() == UserRole.VIEWER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("只读观察员无权创建智能体资产"));
        }
        if (agent.getName() == null || agent.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("智能体名称不能为空"));
        }
        if (agent.getCategory() == null || agent.getCategory().trim().isEmpty()) {
            agent.setCategory("通用智能");
        }
        try {
            Agent created = agentService.create(agent, actor);
            URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                    .path("/{id}")
                    .buildAndExpand(created.getId())
                    .toUri();
            return ResponseEntity.created(location).body(ApiResponse.ok("智能体创建成功", created));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Agent>> updateAgent(@PathVariable String id, @RequestBody Agent agent) {
        try {
            Agent updated = agentService.update(id, agent, CurrentActor.get());
            return ResponseEntity.ok(ApiResponse.ok("智能体配置已成功更新", updated));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<Agent>> updateStatus(@PathVariable String id, @RequestBody AgentStatusRequest request) {
        try {
            Agent updated = agentService.updateStatus(id, request.getStatus(), CurrentActor.get());
            return ResponseEntity.ok(ApiResponse.ok("智能体状态已变更", updated));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            HttpStatus status = message != null && message.contains("不存在") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(ApiResponse.error(message));
        }
    }

    @PostMapping("/{id}/regenerate-api-key")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> regenerateApiKey(@PathVariable String id) {
        return agentService.getById(id).map(agent -> {
            CurrentActor actor = CurrentActor.get();
            if (!authService.canManageAgent(actor, agent)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .<ApiResponse<java.util.Map<String, Object>>>body(ApiResponse.error("权限不足：无法重置他人智能体的 API Key"));
            }
            try {
                java.util.Map<String, Object> created = openApiKeyService.createChatKeyForAgent(agent, actor);
                return ResponseEntity.ok(ApiResponse.ok("已签发新的开放凭证，明文仅显示一次", created));
            } catch (IllegalArgumentException | IllegalStateException e) {
                return ResponseEntity.badRequest().<ApiResponse<java.util.Map<String, Object>>>body(ApiResponse.error(e.getMessage()));
            }
        }).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("未找到指定的智能体: " + id)));
    }

    @PostMapping("/{id}/copy")
    public ResponseEntity<ApiResponse<Agent>> copyAgent(@PathVariable String id) {
        try {
            Agent cloned = agentService.copyAgent(id, CurrentActor.get());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.ok("已成功将智能体复制为您的专属资产", cloned));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteAgent(@PathVariable String id) {
        try {
            boolean removed = agentService.delete(id, CurrentActor.get());
            if (removed) {
                return ResponseEntity.ok(ApiResponse.ok("智能体已成功删除", null));
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("删除失败，未找到该智能体"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@PathVariable String id,
                                                          @RequestBody ChatRequest request,
                                                          HttpSession session) {
        if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("发送内容不能为空"));
        }
        request.setAgentId(id);
        if (request.getAccount() == null || request.getAccount().isBlank()) {
            LoginResponse user = (LoginResponse) session.getAttribute(SessionAuthInterceptor.SESSION_USER);
            if (user != null && user.getUsername() != null) {
                request.setAccount(user.getUsername());
            }
        }
        try {
            ChatResponse response = aiChatService.chat(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("当前无法完成模型调用，请检查模型网关配置或稍后重试"));
        }
    }

    @GetMapping("/{id}/logs")
    public ResponseEntity<ApiResponse<PageResult<AgentConversation>>> listLogs(
            @PathVariable String id,
            @RequestParam(defaultValue = "7days") String range,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Agent agent = agentService.getById(id).orElse(null);
        if (agent == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("未找到指定的智能体: " + id));
        }
        CurrentActor actor = CurrentActor.get();
        if (!authService.canViewAgent(actor, agent)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("权限不足：无法访问该智能体的会话日志"));
        }
        return ResponseEntity.ok(ApiResponse.ok(
                conversationService.listLogs(agent, actor, range, keyword, sort, order, page, size)));
    }

    @GetMapping("/{id}/logs/{conversationId}")
    public ResponseEntity<ApiResponse<ConversationDetail>> getLogDetail(@PathVariable String id,
                                                                        @PathVariable String conversationId) {
        Agent agent = agentService.getById(id).orElse(null);
        if (agent == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("未找到指定智能体"));
        }
        CurrentActor actor = CurrentActor.get();
        if (!authService.canViewAgent(actor, agent)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("权限不足：无法访问该智能体的会话日志"));
        }
        try {
            return conversationService.getDetail(agent, actor, conversationId)
                    .map(detail -> ResponseEntity.ok(ApiResponse.ok(detail)))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(ApiResponse.error("未找到指定会话日志")));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}/monitor")
    public ResponseEntity<ApiResponse<AgentMonitorStats>> monitor(
            @PathVariable String id,
            @RequestParam(defaultValue = "7days") String range) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(agentService.getAgentMonitor(id, range)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }
}
