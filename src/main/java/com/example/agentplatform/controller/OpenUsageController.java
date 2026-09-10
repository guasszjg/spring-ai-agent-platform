package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.AuditEvent;
import com.example.agentplatform.model.UsageDaily;
import com.example.agentplatform.repository.AuditEventRepository;
import com.example.agentplatform.security.OpenApiContext;
import com.example.agentplatform.security.OpenApiScopes;
import com.example.agentplatform.service.UsageRecorder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/open/v1")
public class OpenUsageController {

    private final UsageRecorder usageRecorder;
    private final AuditEventRepository auditEventRepository;

    public OpenUsageController(UsageRecorder usageRecorder, AuditEventRepository auditEventRepository) {
        this.usageRecorder = usageRecorder;
        this.auditEventRepository = auditEventRepository;
    }

    @GetMapping("/usage/summary")
    public ResponseEntity<?> getUsageSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.USAGE_READ)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 usage:read 权限");
        }
        LocalDate endDate = to != null ? to : LocalDate.now();
        LocalDate startDate = from != null ? from : endDate.minusDays(30);
        String ownerId = ctx.getOwner().getId();

        Map<String, Object> summary = usageRecorder.getSummary(ownerId, startDate, endDate);
        summary.put("from", startDate.toString());
        summary.put("to", endDate.toString());
        return ResponseEntity.ok(ApiResponse.ok(summary));
    }

    @GetMapping("/usage/daily")
    public ResponseEntity<?> getUsageDaily(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.USAGE_READ)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 usage:read 权限");
        }
        LocalDate endDate = to != null ? to : LocalDate.now();
        LocalDate startDate = from != null ? from : endDate.minusDays(30);
        String ownerId = ctx.getOwner().getId();

        List<UsageDaily> list = usageRecorder.getDailyList(ownerId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    @GetMapping("/audit/events")
    public ResponseEntity<?> getAuditEvents(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String result) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.USAGE_READ)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 usage:read 权限");
        }
        LocalDate endDate = to != null ? to : LocalDate.now();
        LocalDate startDate = from != null ? from : endDate.minusDays(7);
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(LocalTime.MAX);
        String ownerId = ctx.getOwner().getId();

        List<AuditEvent> events = auditEventRepository.findByOwnerIdAndOccurredAtBetweenOrderByOccurredAtDesc(ownerId, start, end);
        if (result != null && !result.isBlank()) {
            events = events.stream().filter(e -> result.equalsIgnoreCase(e.getResult())).toList();
        }
        return ResponseEntity.ok(ApiResponse.ok(events));
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
