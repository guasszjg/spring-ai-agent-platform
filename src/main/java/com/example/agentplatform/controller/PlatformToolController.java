package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.EnabledToggleRequest;
import com.example.agentplatform.model.PageResult;
import com.example.agentplatform.model.PlatformToolUpdateRequest;
import com.example.agentplatform.model.PlatformToolView;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.PlatformToolService;
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

@RestController
@RequestMapping("/api/platform-tools")
public class PlatformToolController {

    private final PlatformToolService platformToolService;

    public PlatformToolController(PlatformToolService platformToolService) {
        this.platformToolService = platformToolService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<?>> list(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String category) {
        if (page != null) {
            PageResult<PlatformToolView> result = platformToolService.page(keyword, category, page, size == null ? 6 : size);
            return ResponseEntity.ok(ApiResponse.ok(result));
        }
        return ResponseEntity.ok(ApiResponse.ok(platformToolService.list()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PlatformToolView>> create(@RequestBody PlatformToolUpdateRequest request) {
        if (!canManage()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅管理员或开发者可添加自定义工具"));
        }
        try {
            return ResponseEntity.ok(ApiResponse.ok("自定义工具已创建，可在调试页添加使用", platformToolService.create(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        if (!canManage()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅管理员或开发者可删除自定义工具"));
        }
        try {
            platformToolService.delete(id);
            return ResponseEntity.ok(ApiResponse.ok("自定义工具已删除", null));
        } catch (IllegalArgumentException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().contains("未找到")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PlatformToolView>> get(@PathVariable String id) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(platformToolService.get(id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<PlatformToolView>> update(@PathVariable String id,
                                                                @RequestBody PlatformToolUpdateRequest request) {
        if (!canManage()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅管理员或开发者可配置平台工具"));
        }
        try {
            return ResponseEntity.ok(ApiResponse.ok("工具配置已保存，所有智能体将使用该配置", platformToolService.update(id, request)));
        } catch (IllegalArgumentException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().contains("未找到")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PatchMapping("/{id}/enabled")
    public ResponseEntity<ApiResponse<PlatformToolView>> setEnabled(@PathVariable String id,
                                                                    @RequestBody EnabledToggleRequest request) {
        if (!canManage()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅管理员或开发者可启停平台工具"));
        }
        try {
            boolean enabled = request != null && Boolean.TRUE.equals(request.getEnabled());
            return ResponseEntity.ok(ApiResponse.ok(enabled ? "工具已对全部智能体开放" : "工具已停用",
                    platformToolService.setEnabled(id, enabled)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/{id}/test-connection")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testConnection(@PathVariable String id,
                                                                           @RequestBody(required = false) Map<String, String> body) {
        if (!canManage()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅管理员或开发者可测试工具连通性"));
        }
        try {
            String apiKey = body != null ? body.get("apiKey") : null;
            Map<String, Object> result = platformToolService.testConnection(id, apiKey);
            boolean success = Boolean.TRUE.equals(result.get("success"));
            String msg = String.valueOf(result.getOrDefault("message", success ? "测试成功" : "测试失败"));
            if (success) {
                return ResponseEntity.ok(ApiResponse.ok(msg, result));
            }
            return ResponseEntity.ok(ApiResponse.error(msg, result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    private boolean canManage() {
        CurrentActor actor = CurrentActor.get();
        return actor != null && !actor.isViewer();
    }
}
