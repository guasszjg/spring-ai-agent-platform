package com.example.agentplatform.controller;

import com.example.agentplatform.model.AgentTemplate;
import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.AgentTemplateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent-templates")
public class AgentTemplateController {

    private final AgentTemplateService templateService;

    public AgentTemplateController(AgentTemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<?>> listTemplates(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String ownerId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        if (page != null && size != null) {
            return ResponseEntity.ok(ApiResponse.ok(templateService.searchTemplates(keyword, category, ownerId, page, size, CurrentActor.get())));
        }
        return ResponseEntity.ok(ApiResponse.ok(templateService.list(keyword, category, ownerId, CurrentActor.get())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AgentTemplate>> getTemplate(@PathVariable String id) {
        try {
            return templateService.getById(id)
                    .map(t -> ResponseEntity.ok(ApiResponse.ok(t)))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("模板未找到: " + id)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AgentTemplate>> createTemplate(@RequestBody AgentTemplate template) {
        try {
            AgentTemplate created = templateService.create(template);
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("场景模板创建成功", created));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AgentTemplate>> updateTemplate(@PathVariable String id, @RequestBody AgentTemplate template) {
        try {
            AgentTemplate updated = templateService.update(id, template);
            return ResponseEntity.ok(ApiResponse.ok("场景模板更新成功", updated));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTemplate(@PathVariable String id) {
        try {
            boolean removed = templateService.delete(id);
            if (removed) {
                return ResponseEntity.ok(ApiResponse.ok("场景模板已删除", null));
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("删除失败，未找到该模板"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        }
    }
}
