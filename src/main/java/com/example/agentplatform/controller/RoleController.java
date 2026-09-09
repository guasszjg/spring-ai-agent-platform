package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.RoleDto;
import com.example.agentplatform.service.UserAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final UserAdminService userAdminService;

    public RoleController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<RoleDto>>> getRoles() {
        return ResponseEntity.ok(ApiResponse.ok(userAdminService.getRolesSummary()));
    }
}
