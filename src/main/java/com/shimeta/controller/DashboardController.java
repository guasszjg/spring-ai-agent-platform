package com.shimeta.controller;

import com.shimeta.model.ApiResponse;
import com.shimeta.model.DashboardStats;
import com.shimeta.service.AgentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final AgentService agentService;

    public DashboardController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<DashboardStats>> getStats(
            @RequestParam(defaultValue = "7days") String range) {
        return ResponseEntity.ok(ApiResponse.ok(agentService.getDashboardStats(range)));
    }
}
