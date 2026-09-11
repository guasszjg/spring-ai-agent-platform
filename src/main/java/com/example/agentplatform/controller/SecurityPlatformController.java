package com.example.agentplatform.controller;

import com.example.agentplatform.identity.IdentitySyncResult;
import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.AuditEvent;
import com.example.agentplatform.model.ClientCredential;
import com.example.agentplatform.model.ExternalIdentity;
import com.example.agentplatform.model.GuardrailPolicy;
import com.example.agentplatform.model.IdentityBindRequest;
import com.example.agentplatform.model.IdentityProvider;
import com.example.agentplatform.model.PageResult;
import com.example.agentplatform.repository.AuditEventRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.IdentitySyncService;
import com.example.agentplatform.service.SecurityPlatformService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

import com.example.agentplatform.model.OpenApiCallLog;
import com.example.agentplatform.model.UsageDaily;
import com.example.agentplatform.repository.OpenApiCallLogRepository;
import com.example.agentplatform.service.UsageRecorder;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/security")
public class SecurityPlatformController {

    private final SecurityPlatformService securityPlatformService;
    private final IdentitySyncService identitySyncService;
    private final AuditEventRepository auditEventRepository;
    private final UsageRecorder usageRecorder;
    private final OpenApiCallLogRepository openApiCallLogRepository;

    public SecurityPlatformController(SecurityPlatformService securityPlatformService,
                                      IdentitySyncService identitySyncService,
                                      AuditEventRepository auditEventRepository,
                                      UsageRecorder usageRecorder,
                                      OpenApiCallLogRepository openApiCallLogRepository) {
        this.securityPlatformService = securityPlatformService;
        this.identitySyncService = identitySyncService;
        this.auditEventRepository = auditEventRepository;
        this.usageRecorder = usageRecorder;
        this.openApiCallLogRepository = openApiCallLogRepository;
    }

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview() {
        return ResponseEntity.ok(ApiResponse.ok(securityPlatformService.overview(CurrentActor.get())));
    }

    @GetMapping("/policy")
    public ResponseEntity<ApiResponse<GuardrailPolicy>> policy() {
        return ResponseEntity.ok(ApiResponse.ok(securityPlatformService.loadPolicy(CurrentActor.get())));
    }

    @PutMapping("/policy")
    public ResponseEntity<ApiResponse<GuardrailPolicy>> savePolicy(@RequestBody GuardrailPolicy policy) {
        try {
            return ResponseEntity.ok(ApiResponse.ok("策略已保存并立即生效", securityPlatformService.savePolicy(policy, CurrentActor.get())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/clients")
    public ResponseEntity<ApiResponse<List<ClientCredential>>> clients() {
        return ResponseEntity.ok(ApiResponse.ok(securityPlatformService.listClients(CurrentActor.get())));
    }

    @PostMapping("/clients")
    public ResponseEntity<ApiResponse<ClientCredential>> saveClient(@RequestBody ClientCredential body) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(securityPlatformService.saveClient(body, CurrentActor.get())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/clients/{id}/approve")
    public ResponseEntity<ApiResponse<ClientCredential>> approve(@PathVariable String id) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(securityPlatformService.approve(id, CurrentActor.get())));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/clients/{id}/status")
    public ResponseEntity<ApiResponse<ClientCredential>> clientStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(securityPlatformService.updateClientStatus(id, body.get("status"), CurrentActor.get())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/clients/batch-approve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchApprove(@RequestBody Map<String, List<String>> body) {
        try {
            List<String> ids = body != null ? body.get("ids") : List.of();
            return ResponseEntity.ok(ApiResponse.ok("审批完成", securityPlatformService.batchApprove(ids, CurrentActor.get())));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping(value = "/clients/export-csv", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<String> exportCsv(jakarta.servlet.http.HttpServletResponse response) {
        try {
            response.setHeader("Content-Disposition", "attachment; filename=clients.csv");
            return ResponseEntity.ok(securityPlatformService.exportClientsCsv(CurrentActor.get()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("导出失败: " + e.getMessage());
        }
    }

    @PostMapping("/clients/import-csv")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importCsv(@RequestBody Map<String, String> body) {
        try {
            String csv = body != null ? body.get("csv") : null;
            return ResponseEntity.ok(ApiResponse.ok("导入完成", securityPlatformService.importClientsCsv(csv, CurrentActor.get())));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/clients/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteClient(@PathVariable String id) {
        try {
            securityPlatformService.deleteClient(id, CurrentActor.get());
            return ResponseEntity.ok(ApiResponse.ok("已删除", null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/audit-events")
    public ResponseEntity<ApiResponse<PageResult<AuditEvent>>> auditEvents(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String result) {
        CurrentActor actor = CurrentActor.get();
        int pageNum = Math.max(page, 1);
        int pageSize = Math.min(Math.max(size, 1), 100);
        PageRequest pr = PageRequest.of(pageNum - 1, pageSize);
        boolean admin = actor != null && actor.isSuperAdmin();
        String ownerId = actor != null ? actor.getUserId() : "";

        Page<AuditEvent> pageRes;
        if (riskLevel != null && !riskLevel.isBlank()) {
            pageRes = admin ? auditEventRepository.findByRiskLevelOrderByOccurredAtDesc(riskLevel, pr)
                            : auditEventRepository.findByOwnerIdAndRiskLevelOrderByOccurredAtDesc(ownerId, riskLevel, pr);
        } else if (result != null && !result.isBlank()) {
            pageRes = admin ? auditEventRepository.findByResultOrderByOccurredAtDesc(result, pr)
                            : auditEventRepository.findByOwnerIdAndResultOrderByOccurredAtDesc(ownerId, result, pr);
        } else {
            pageRes = admin ? auditEventRepository.findAllByOrderByOccurredAtDesc(pr)
                            : auditEventRepository.findByOwnerIdOrderByOccurredAtDesc(ownerId, pr);
        }
        return ResponseEntity.ok(ApiResponse.ok(new PageResult<>(pageRes.getContent(), pageRes.getTotalElements(), pageNum, pageSize)));
    }

    @GetMapping(value = "/audit-events/export-csv", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<String> exportAuditEventsCsv(
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String result,
            jakarta.servlet.http.HttpServletResponse response) {
        try {
            response.setHeader("Content-Disposition", "attachment; filename=audit_events.csv");
            return ResponseEntity.ok(securityPlatformService.exportAuditEventsCsv(CurrentActor.get(), riskLevel, result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("导出失败: " + e.getMessage());
        }
    }

    @GetMapping("/identity-providers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> providers() {
        CurrentActor actor = CurrentActor.get();
        if (actor == null || (!actor.isSuperAdmin() && !actor.isDeveloper())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("无权查看对接通道"));
        }
        List<Map<String, Object>> list = identitySyncService.listProviders().stream()
                .map(identitySyncService::toProviderView)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    @PostMapping("/identity-providers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveProvider(@RequestBody Map<String, Object> body) {
        try {
            IdentityProvider provider = new IdentityProvider();
            if (body.get("id") != null) provider.setId(String.valueOf(body.get("id")));
            if (body.get("code") != null) provider.setCode(String.valueOf(body.get("code")));
            if (body.get("name") != null) provider.setName(String.valueOf(body.get("name")));
            if (body.get("enabled") != null) provider.setEnabled(Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
            if (body.get("direction") != null) provider.setDirection(String.valueOf(body.get("direction")));
            if (body.get("baseUrl") != null) provider.setBaseUrl(String.valueOf(body.get("baseUrl")));
            if (body.get("authType") != null) provider.setAuthType(String.valueOf(body.get("authType")));
            if (body.get("timeoutMs") != null) provider.setTimeoutMs(Integer.parseInt(String.valueOf(body.get("timeoutMs"))));
            if (body.get("operations") != null) provider.setOperations(String.valueOf(body.get("operations")));
            if (body.get("fieldMapping") != null) provider.setFieldMapping(String.valueOf(body.get("fieldMapping")));
            if (body.get("onUserCreated") != null) provider.setOnUserCreated(String.valueOf(body.get("onUserCreated")));
            if (body.get("onUserDisabled") != null) provider.setOnUserDisabled(String.valueOf(body.get("onUserDisabled")));
            if (body.get("failPolicy") != null) provider.setFailPolicy(String.valueOf(body.get("failPolicy")));
            String credential = body.get("credential") != null ? String.valueOf(body.get("credential")) : null;
            String webhook = body.get("webhookSecret") != null ? String.valueOf(body.get("webhookSecret")) : null;
            IdentityProvider saved = identitySyncService.saveProvider(provider, credential, webhook, CurrentActor.get());
            return ResponseEntity.ok(ApiResponse.ok(identitySyncService.toProviderView(saved)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/identity-providers/{id}/probe")
    public ResponseEntity<ApiResponse<Map<String, Object>>> probe(@PathVariable String id) {
        IdentitySyncResult result = identitySyncService.probe(id);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "success", result.success(),
                "message", result.success() ? "连接正常" : result.error()
        )));
    }

    @DeleteMapping("/identity-providers/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteProvider(@PathVariable String id) {
        try {
            identitySyncService.deleteProvider(id, CurrentActor.get());
            return ResponseEntity.ok(ApiResponse.ok("对接通道已删除", null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/identities")
    public ResponseEntity<ApiResponse<List<ExternalIdentity>>> myIdentities(@RequestParam(required = false) String userId) {
        CurrentActor actor = CurrentActor.get();
        String target = userId;
        if (target == null || target.isBlank() || (actor != null && !actor.isSuperAdmin())) {
            target = actor != null ? actor.getUserId() : "";
        }
        return ResponseEntity.ok(ApiResponse.ok(identitySyncService.listByUser(target)));
    }

    @PostMapping("/identities")
    public ResponseEntity<ApiResponse<ExternalIdentity>> bind(@RequestBody IdentityBindRequest request,
                                                              @RequestParam(required = false) String userId) {
        try {
            CurrentActor actor = CurrentActor.get();
            String target = userId;
            if (target == null || target.isBlank() || (actor != null && !actor.isSuperAdmin())) {
                target = actor != null ? actor.getUserId() : "";
            }
            return ResponseEntity.ok(ApiResponse.ok(identitySyncService.bind(target, request, false)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/identities/{id}/sync")
    public ResponseEntity<ApiResponse<ExternalIdentity>> sync(@PathVariable String id) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(identitySyncService.syncNow(id)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/identities/{id}")
    public ResponseEntity<ApiResponse<Void>> unbind(@PathVariable String id) {
        try {
            identitySyncService.delete(id, CurrentActor.get());
            return ResponseEntity.ok(ApiResponse.ok("已解绑", null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/usage/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> usageSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        CurrentActor actor = CurrentActor.get();
        String ownerId = (actor != null && !actor.isSuperAdmin()) ? actor.getUserId() : null;
        LocalDate endDate = to != null ? to : LocalDate.now();
        LocalDate startDate = from != null ? from : endDate.minusDays(30);
        Map<String, Object> summary = usageRecorder.getSummary(ownerId, startDate, endDate);
        summary.put("from", startDate.toString());
        summary.put("to", endDate.toString());
        return ResponseEntity.ok(ApiResponse.ok(summary));
    }

    @GetMapping("/usage/daily")
    public ResponseEntity<ApiResponse<List<UsageDaily>>> usageDaily(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        CurrentActor actor = CurrentActor.get();
        String ownerId = (actor != null && !actor.isSuperAdmin()) ? actor.getUserId() : null;
        LocalDate endDate = to != null ? to : LocalDate.now();
        LocalDate startDate = from != null ? from : endDate.minusDays(30);
        List<UsageDaily> list = usageRecorder.getDailyList(ownerId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    @GetMapping("/usage/logs")
    public ResponseEntity<ApiResponse<PageResult<OpenApiCallLog>>> usageLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        CurrentActor actor = CurrentActor.get();
        int pageNum = Math.max(page, 1);
        int pageSize = Math.min(Math.max(size, 1), 100);
        PageRequest pr = PageRequest.of(pageNum - 1, pageSize);
        Page<OpenApiCallLog> result = (actor != null && actor.isSuperAdmin())
                ? openApiCallLogRepository.findAllByOrderByTsDesc(pr)
                : openApiCallLogRepository.findByOwnerIdOrderByTsDesc(actor != null ? actor.getUserId() : "", pr);
        return ResponseEntity.ok(ApiResponse.ok(new PageResult<>(result.getContent(), result.getTotalElements(), pageNum, pageSize)));
    }
}
