package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.ExternalIdentity;
import com.example.agentplatform.model.IdentityProvider;
import com.example.agentplatform.repository.ExternalIdentityRepository;
import com.example.agentplatform.repository.IdentityProviderRepository;
import com.example.agentplatform.security.OpenApiContext;
import com.example.agentplatform.security.OpenApiScopes;
import com.example.agentplatform.service.AuditRecorder;
import com.example.agentplatform.service.UsageRecorder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/open/v1")
public class OpenAccountController {

    private final ExternalIdentityRepository externalIdentityRepository;
    private final IdentityProviderRepository identityProviderRepository;
    private final AuditRecorder auditRecorder;
    private final UsageRecorder usageRecorder;

    public OpenAccountController(ExternalIdentityRepository externalIdentityRepository,
                                 IdentityProviderRepository identityProviderRepository,
                                 AuditRecorder auditRecorder,
                                 UsageRecorder usageRecorder) {
        this.externalIdentityRepository = externalIdentityRepository;
        this.identityProviderRepository = identityProviderRepository;
        this.auditRecorder = auditRecorder;
        this.usageRecorder = usageRecorder;
    }

    @GetMapping("/account")
    public ResponseEntity<?> getAccount() {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.ACCOUNT_READ)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 account:read 权限");
        }
        AppUser owner = ctx.getOwner();
        List<ExternalIdentity> identities = externalIdentityRepository.findByUserIdOrderByBoundAtDesc(owner.getId());

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", owner.getId());
        res.put("username", owner.getUsername());
        res.put("nickname", owner.getNickname());
        res.put("role", owner.getRole());
        res.put("status", owner.getStatus());
        res.put("identities", identities);

        usageRecorder.record("account.get", null, null, 200, null, 5, 0, 0, null);
        return ResponseEntity.ok(ApiResponse.ok(res));
    }

    @GetMapping("/account/identities")
    public ResponseEntity<?> getIdentities() {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.ACCOUNT_READ)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 account:read 权限");
        }
        List<ExternalIdentity> identities = externalIdentityRepository.findByUserIdOrderByBoundAtDesc(ctx.getOwner().getId());
        return ResponseEntity.ok(ApiResponse.ok(identities));
    }

    @PostMapping("/account/identities")
    public ResponseEntity<?> bindIdentity(@RequestBody Map<String, String> body) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.ACCOUNT_WRITE)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 account:write 权限");
        }
        String providerCode = body.get("provider_code");
        String externalId = body.get("external_id");
        String displayName = body.get("display_name");
        if (providerCode == null || providerCode.isBlank() || externalId == null || externalId.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_parameter", "provider_code 和 external_id 不能为空");
        }
        Optional<ExternalIdentity> existing = externalIdentityRepository.findByProviderCodeAndExternalId(providerCode, externalId);
        if (existing.isPresent()) {
            return error(HttpStatus.CONFLICT, "identity_conflict", "该外部身份已被绑定");
        }
        IdentityProvider idp = identityProviderRepository.findByCode(providerCode).orElse(null);

        ExternalIdentity identity = new ExternalIdentity();
        identity.setUserId(ctx.getOwner().getId());
        identity.setProviderId(idp != null ? idp.getId() : null);
        identity.setProviderCode(providerCode);
        identity.setExternalId(externalId);
        identity.setDisplayName(displayName != null ? displayName : externalId);
        identity.setBindSource("INBOUND_API");
        identity.setStatus("ACTIVE");
        identity.setBoundAt(LocalDateTime.now());
        identity.setLastSyncedAt(LocalDateTime.now());
        ExternalIdentity saved = externalIdentityRepository.save(identity);

        auditRecorder.record("identity.bind", "IDENTITY", saved.getId(), "SUCCESS", null, "LOW", providerCode + ":" + externalId);
        usageRecorder.record("account.bind_identity", null, null, 200, null, 15, 0, 0, null);
        return ResponseEntity.ok(ApiResponse.ok("绑定成功", saved));
    }

    @DeleteMapping("/account/identities/{id}")
    public ResponseEntity<?> unbindIdentity(@PathVariable String id) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.ACCOUNT_WRITE)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 account:write 权限");
        }
        ExternalIdentity identity = externalIdentityRepository.findById(id).orElse(null);
        if (identity == null) {
            return error(HttpStatus.NOT_FOUND, "identity_not_found", "绑定的外部身份不存在");
        }
        if (!identity.getUserId().equals(ctx.getOwner().getId())) {
            return error(HttpStatus.FORBIDDEN, "account_out_of_scope", "无权解绑该外部身份");
        }
        externalIdentityRepository.delete(identity);
        auditRecorder.record("identity.unbind", "IDENTITY", id, "SUCCESS", null, "LOW", identity.getProviderCode() + ":" + identity.getExternalId());
        return ResponseEntity.ok(ApiResponse.ok("解绑成功", Map.of("id", id)));
    }

    @PostMapping("/webhooks/identity/{providerCode}")
    public ResponseEntity<?> handleWebhook(@PathVariable String providerCode, @RequestBody(required = false) Map<String, Object> payload) {
        // Webhook receiver for third party sync callbacks
        return ResponseEntity.ok(ApiResponse.ok("Webhook received", Map.of("provider", providerCode, "status", "PROCESSED")));
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
