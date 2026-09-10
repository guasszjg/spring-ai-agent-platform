package com.example.agentplatform.controller;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentConversation;
import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.repository.AgentConversationRepository;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.security.OpenApiContext;
import com.example.agentplatform.security.OpenApiScopes;
import com.example.agentplatform.service.AgentConversationService;
import com.example.agentplatform.service.ResourceAuthorizationService;
import com.example.agentplatform.service.UsageRecorder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/open/v1/conversations")
public class OpenConversationController {

    private final AgentConversationRepository conversationRepository;
    private final com.example.agentplatform.repository.AgentConversationMessageRepository messageRepository;
    private final AgentRepository agentRepository;
    private final ResourceAuthorizationService authorizationService;
    private final UsageRecorder usageRecorder;

    public OpenConversationController(AgentConversationRepository conversationRepository,
                                      com.example.agentplatform.repository.AgentConversationMessageRepository messageRepository,
                                      AgentRepository agentRepository,
                                      ResourceAuthorizationService authorizationService,
                                      UsageRecorder usageRecorder) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.agentRepository = agentRepository;
        this.authorizationService = authorizationService;
        this.usageRecorder = usageRecorder;
    }

    @GetMapping
    public ResponseEntity<?> listConversations(@RequestParam(required = false) String agent_id,
                                               @RequestParam(required = false) String end_user) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.CONVERSATIONS_READ)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 conversations:read 权限");
        }
        List<AgentConversation> list;
        if (agent_id != null && !agent_id.isBlank()) {
            Agent agent = agentRepository.findById(agent_id).orElse(null);
            if (agent == null || !authorizationService.canViewAgent(ctx.asActor(), agent)) {
                return error(HttpStatus.FORBIDDEN, "agent_out_of_scope", "无权查看该智能体的会话");
            }
            list = conversationRepository.findByAgentIdOrderByUpdatedAtDesc(agent_id);
        } else {
            list = conversationRepository.findAll();
        }
        if (end_user != null && !end_user.isBlank()) {
            list = list.stream().filter(c -> end_user.equals(c.getAccount())).toList();
        }
        usageRecorder.record("conversations.list", agent_id, null, 200, null, 10, 0, 0, null);
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<?> getMessages(@PathVariable String id) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.CONVERSATIONS_READ)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 conversations:read 权限");
        }
        AgentConversation conv = conversationRepository.findById(id).orElse(null);
        if (conv == null) {
            return error(HttpStatus.NOT_FOUND, "conversation_not_found", "会话不存在");
        }
        Agent agent = agentRepository.findById(conv.getAgentId()).orElse(null);
        if (agent != null && !authorizationService.canViewAgent(ctx.asActor(), agent)) {
            return error(HttpStatus.FORBIDDEN, "agent_out_of_scope", "无权查看该会话消息");
        }
        usageRecorder.record("conversations.messages", conv.getAgentId(), id, 200, null, 10, 0, 0, null);
        return ResponseEntity.ok(ApiResponse.ok(messageRepository.findByConversationIdOrderByCreatedAtAsc(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteConversation(@PathVariable String id) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || (!ctx.hasScope(OpenApiScopes.CHAT) && !ctx.hasScope(OpenApiScopes.CONVERSATIONS_READ))) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 chat 或 conversations:read 权限");
        }
        AgentConversation conv = conversationRepository.findById(id).orElse(null);
        if (conv == null) {
            return error(HttpStatus.NOT_FOUND, "conversation_not_found", "会话不存在");
        }
        Agent agent = agentRepository.findById(conv.getAgentId()).orElse(null);
        if (agent != null && !authorizationService.canManageAgent(ctx.asActor(), agent)) {
            return error(HttpStatus.FORBIDDEN, "agent_out_of_scope", "无权删除该会话");
        }
        messageRepository.deleteByConversationId(id);
        conversationRepository.delete(conv);
        usageRecorder.record("conversations.delete", conv.getAgentId(), id, 200, null, 10, 0, 0, null);
        return ResponseEntity.ok(ApiResponse.ok("会话已删除", Map.of("id", id)));
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
