package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.ResourceGrant;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ShareController {

    private final ResourceAuthorizationService authService;

    public ShareController(ResourceAuthorizationService authService) {
        this.authService = authService;
    }

    @GetMapping("/share-candidates")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listCandidates(@RequestParam(required = false) String q) {
        return ResponseEntity.ok(ApiResponse.ok(authService.listShareCandidates(q, CurrentActor.get())));
    }

    // ==================== Agent Grants ====================

    @GetMapping("/agents/{id}/grants")
    public ResponseEntity<ApiResponse<List<ResourceGrant>>> listAgentGrants(@PathVariable String id) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(authService.listGrants(ResourceAuthorizationService.TYPE_AGENT, id, CurrentActor.get())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/agents/{id}/grants/{userId}")
    public ResponseEntity<ApiResponse<ResourceGrant>> grantAgent(
            @PathVariable String id,
            @PathVariable String userId,
            @RequestBody Map<String, String> body) {
        try {
            String level = body != null ? body.get("level") : null;
            ResourceGrant grant = authService.grantAccess(ResourceAuthorizationService.TYPE_AGENT, id, userId, level, CurrentActor.get());
            return ResponseEntity.ok(ApiResponse.ok("智能体授权成功", grant));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/agents/{id}/grants/{userId}")
    public ResponseEntity<ApiResponse<Void>> revokeAgent(
            @PathVariable String id,
            @PathVariable String userId) {
        try {
            authService.revokeAccess(ResourceAuthorizationService.TYPE_AGENT, id, userId, CurrentActor.get());
            return ResponseEntity.ok(ApiResponse.ok("智能体授权已撤销", null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== KnowledgeBase Grants ====================

    @GetMapping("/knowledge-bases/{id}/grants")
    public ResponseEntity<ApiResponse<List<ResourceGrant>>> listKbGrants(@PathVariable String id) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(authService.listGrants(ResourceAuthorizationService.TYPE_KNOWLEDGE_BASE, id, CurrentActor.get())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/knowledge-bases/{id}/grants/{userId}")
    public ResponseEntity<ApiResponse<ResourceGrant>> grantKb(
            @PathVariable String id,
            @PathVariable String userId,
            @RequestBody Map<String, String> body) {
        try {
            String level = body != null ? body.get("level") : null;
            ResourceGrant grant = authService.grantAccess(ResourceAuthorizationService.TYPE_KNOWLEDGE_BASE, id, userId, level, CurrentActor.get());
            return ResponseEntity.ok(ApiResponse.ok("知识库授权成功", grant));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/knowledge-bases/{id}/grants/{userId}")
    public ResponseEntity<ApiResponse<Void>> revokeKb(
            @PathVariable String id,
            @PathVariable String userId) {
        try {
            authService.revokeAccess(ResourceAuthorizationService.TYPE_KNOWLEDGE_BASE, id, userId, CurrentActor.get());
            return ResponseEntity.ok(ApiResponse.ok("知识库授权已撤销", null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== Template Grants ====================

    @GetMapping("/agent-templates/{id}/grants")
    public ResponseEntity<ApiResponse<List<ResourceGrant>>> listTemplateGrants(@PathVariable String id) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(authService.listGrants(ResourceAuthorizationService.TYPE_TEMPLATE, id, CurrentActor.get())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/agent-templates/{id}/grants/{userId}")
    public ResponseEntity<ApiResponse<ResourceGrant>> grantTemplate(
            @PathVariable String id,
            @PathVariable String userId,
            @RequestBody Map<String, String> body) {
        try {
            String level = body != null ? body.get("level") : null;
            ResourceGrant grant = authService.grantAccess(ResourceAuthorizationService.TYPE_TEMPLATE, id, userId, level, CurrentActor.get());
            return ResponseEntity.ok(ApiResponse.ok("模板授权成功", grant));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/agent-templates/{id}/grants/{userId}")
    public ResponseEntity<ApiResponse<Void>> revokeTemplate(
            @PathVariable String id,
            @PathVariable String userId) {
        try {
            authService.revokeAccess(ResourceAuthorizationService.TYPE_TEMPLATE, id, userId, CurrentActor.get());
            return ResponseEntity.ok(ApiResponse.ok("模板授权已撤销", null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
