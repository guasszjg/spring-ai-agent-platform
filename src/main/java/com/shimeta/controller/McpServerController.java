package com.shimeta.controller;

import com.shimeta.mcp.McpServerRequest;
import com.shimeta.mcp.McpServerService;
import com.shimeta.mcp.McpServerView;
import com.shimeta.mcp.McpToolUpdateRequest;
import com.shimeta.mcp.McpToolView;
import com.shimeta.model.ApiResponse;
import com.shimeta.model.EnabledToggleRequest;
import com.shimeta.security.CurrentActor;
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

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * MCP 服务管理。添加服务、审核工具只有超级管理员可以做；
 * 其它登录用户只能看到已经启用的工具，用于在智能体里选用。
 */
@RestController
@RequestMapping("/api/mcp-servers")
public class McpServerController {

    private final McpServerService service;

    public McpServerController(McpServerService service) {
        this.service = service;
    }

    /** 智能体可选的 MCP 工具（已审核启用），不含服务地址和密钥 */
    @GetMapping("/available-tools")
    public ResponseEntity<ApiResponse<List<McpToolView>>> availableTools() {
        if (CurrentActor.get() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("请先登录"));
        }
        return ResponseEntity.ok(ApiResponse.ok(service.availableTools()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<McpServerView>>> list() {
        return admin(null, service::list);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<McpServerView>> get(@PathVariable String id) {
        return admin(null, () -> service.get(id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<McpServerView>> create(@RequestBody McpServerRequest request) {
        return admin("MCP 服务已添加，请测试连接后拉取工具", () -> service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<McpServerView>> update(@PathVariable String id, @RequestBody McpServerRequest request) {
        return admin("MCP 服务已保存", () -> service.update(id, request));
    }

    @PatchMapping("/{id}/enabled")
    public ResponseEntity<ApiResponse<McpServerView>> setEnabled(@PathVariable String id, @RequestBody EnabledToggleRequest request) {
        boolean enabled = request != null && Boolean.TRUE.equals(request.getEnabled());
        return admin(enabled ? "MCP 服务已启用" : "MCP 服务已停用", () -> service.setEnabled(id, enabled));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        return admin("MCP 服务已删除", () -> {
            service.delete(id);
            return null;
        });
    }

    /** 保存前测试填写的地址和鉴权；编辑已有服务时带上 id，没重新填的地址和密钥用已保存的 */
    @PostMapping("/test")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testDraft(@RequestParam(required = false) String id,
                                                                      @RequestBody McpServerRequest request) {
        return probe(() -> service.testDraft(id, request));
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<ApiResponse<Map<String, Object>>> test(@PathVariable String id) {
        return probe(() -> service.test(id));
    }

    @PostMapping("/{id}/refresh")
    public ResponseEntity<ApiResponse<McpServerView>> refresh(@PathVariable String id) {
        if (!isAdmin()) {
            return forbidden();
        }
        try {
            McpServerView view = service.refreshTools(id);
            return ResponseEntity.ok(ApiResponse.ok(view.getLastMessage(), view));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @PatchMapping("/{id}/tools/{toolId}")
    public ResponseEntity<ApiResponse<McpToolView>> updateTool(@PathVariable String id, @PathVariable String toolId,
                                                               @RequestBody McpToolUpdateRequest request) {
        return admin("工具设置已保存", () -> service.updateTool(id, toolId, request));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> probe(Supplier<Map<String, Object>> action) {
        if (!isAdmin()) {
            return forbidden();
        }
        try {
            Map<String, Object> result = action.get();
            String message = String.valueOf(result.get("message"));
            return ResponseEntity.ok(Boolean.TRUE.equals(result.get("success"))
                    ? ApiResponse.ok(message, result) : ApiResponse.error(message, result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    private <T> ResponseEntity<ApiResponse<T>> admin(String message, Supplier<T> action) {
        if (!isAdmin()) {
            return forbidden();
        }
        try {
            T data = action.get();
            return ResponseEntity.ok(message != null ? ApiResponse.ok(message, data) : ApiResponse.ok(data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    private static boolean isAdmin() {
        CurrentActor actor = CurrentActor.get();
        return actor != null && actor.isSuperAdmin();
    }

    private static <T> ResponseEntity<ApiResponse<T>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("权限不足：只有超级管理员可以管理 MCP 服务"));
    }
}
