package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.ChangeUserRoleRequest;
import com.example.agentplatform.model.ChangeUserStatusRequest;
import com.example.agentplatform.model.CreateUserRequest;
import com.example.agentplatform.model.UpdateUserProfileRequest;
import com.example.agentplatform.model.UserSummaryDto;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.UserAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/users")
public class UserController {

    private final UserAdminService userAdminService;

    public UserController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    private boolean checkAdmin() {
        CurrentActor actor = CurrentActor.get();
        return actor != null && actor.isSuperAdmin();
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<UserSummaryDto>>> listUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可查看用户列表"));
        }
        List<UserSummaryDto> list = userAdminService.listUsers(keyword, role, status);
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> createUser(@RequestBody CreateUserRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可创建用户"));
        }
        try {
            Map<String, Object> created = userAdminService.createUser(request);
            return ResponseEntity.ok(ApiResponse.ok("用户创建成功", created));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}/profile")
    public ResponseEntity<ApiResponse<UserSummaryDto>> updateProfile(
            @PathVariable String id,
            @RequestBody UpdateUserProfileRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可修改其他用户资料"));
        }
        try {
            UserSummaryDto updated = userAdminService.updateUserProfile(id, request);
            return ResponseEntity.ok(ApiResponse.ok("用户资料更新成功", updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}/role")
    public ResponseEntity<ApiResponse<UserSummaryDto>> changeRole(
            @PathVariable String id,
            @RequestBody ChangeUserRoleRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可分配用户角色"));
        }
        if (request.getRole() == null || request.getRole().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("目标角色不能为空"));
        }
        try {
            UserSummaryDto updated = userAdminService.changeUserRole(id, request.getRole());
            return ResponseEntity.ok(ApiResponse.ok("角色已成功变更为: " + updated.getRoleName(), updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<UserSummaryDto>> changeStatus(
            @PathVariable String id,
            @RequestBody ChangeUserStatusRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可修改用户状态"));
        }
        if (request.getStatus() == null || request.getStatus().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("目标状态不能为空"));
        }
        try {
            UserSummaryDto updated = userAdminService.changeUserStatus(id, request.getStatus());
            String text = "ACTIVE".equalsIgnoreCase(updated.getStatus()) ? "已恢复正常" : "已被禁用";
            return ResponseEntity.ok(ApiResponse.ok("用户状态更新成功: " + text, updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resetPassword(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可重置用户密码"));
        }
        try {
            String customPassword = null;
            Boolean mustChangePassword = null;
            if (body != null) {
                if (body.get("newPassword") != null) {
                    customPassword = String.valueOf(body.get("newPassword"));
                }
                if (body.get("mustChangePassword") != null) {
                    mustChangePassword = Boolean.valueOf(String.valueOf(body.get("mustChangePassword")));
                }
            }
            Map<String, Object> result = userAdminService.resetPassword(id, customPassword, mustChangePassword);
            String msg = (customPassword != null && !customPassword.trim().isEmpty())
                    ? "用户密码已修改成功"
                    : "临时密码已生成，请安全交付给用户";
            return ResponseEntity.ok(ApiResponse.ok(msg, result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
