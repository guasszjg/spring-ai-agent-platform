package com.example.agentplatform.security;

import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.ClientCredential;
import com.example.agentplatform.model.GuardrailPolicy;
import com.example.agentplatform.model.OpenApiKey;
import com.example.agentplatform.model.UserStatus;
import com.example.agentplatform.repository.ClientCredentialRepository;
import com.example.agentplatform.repository.GuardrailPolicyRepository;
import com.example.agentplatform.repository.UserRepository;
import com.example.agentplatform.service.AuditRecorder;
import com.example.agentplatform.service.GuardrailPolicyService;
import com.example.agentplatform.service.OpenApiKeyService;
import com.example.agentplatform.service.OpenApiKeyService.ResolvedKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class OpenApiAuthFilter extends OncePerRequestFilter {

    private final OpenApiKeyService openApiKeyService;
    private final UserRepository userRepository;
    private final GuardrailPolicyService policyService;
    private final ClientCredentialRepository clientRepository;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    public OpenApiAuthFilter(OpenApiKeyService openApiKeyService,
                             UserRepository userRepository,
                             GuardrailPolicyService policyService,
                             ClientCredentialRepository clientRepository,
                             AuditRecorder auditRecorder,
                             ObjectMapper objectMapper) {
        this.openApiKeyService = openApiKeyService;
        this.userRepository = userRepository;
        this.policyService = policyService;
        this.clientRepository = clientRepository;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        response.setHeader("X-Request-Id", requestId);

        String apiKey = extractApiKey(request);
        if (apiKey == null) {
            write(response, 401, "key_invalid", "API Key 未提供或格式不正确", requestId);
            return;
        }
        Optional<ResolvedKey> resolved = openApiKeyService.resolvePlaintext(apiKey);
        if (resolved.isEmpty()) {
            write(response, 401, "key_invalid", "无效的 API Key", requestId);
            return;
        }
        OpenApiKey key = resolved.get().key();
        if ("REVOKED".equalsIgnoreCase(key.getStatus())) {
            write(response, 401, "key_revoked", "凭证已吊销", requestId);
            return;
        }
        if ("DISABLED".equalsIgnoreCase(key.getStatus())) {
            write(response, 401, "key_revoked", "凭证已停用", requestId);
            return;
        }
        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(LocalDateTime.now())) {
            write(response, 401, "key_expired", "凭证已过期", requestId);
            return;
        }
        AppUser owner = userRepository.findById(key.getOwnerId()).orElse(null);
        if (owner == null || UserStatus.DISABLED.equals(owner.getStatus())) {
            write(response, 401, "key_invalid", "凭证所属开发者不可用", requestId);
            return;
        }
        if (key.getCreatedBy() != null && !resolved.get().isLegacy()) {
            AppUser issuer = userRepository.findById(key.getCreatedBy()).orElse(null);
            if (issuer == null || UserStatus.DISABLED.equals(issuer.getStatus())) {
                write(response, 401, "key_invalid", "签发人账号已失效", requestId);
                return;
            }
            int currentVersion = issuer.getAuthVersion() != null ? issuer.getAuthVersion() : 1;
            if (currentVersion != key.getIssuerAuthVersion()) {
                write(response, 401, "key_revoked", "签发人权限已变更，请重新签发凭证", requestId);
                return;
            }
        }
        String ip = clientIp(request);
        if (!key.getIpAllowlist().isEmpty()) {
            boolean allowed = key.getIpAllowlist().stream().anyMatch(rule -> ipMatches(ip, rule));
            if (!allowed) {
                write(response, 403, "ip_not_allowed", "来源 IP 不在白名单", requestId);
                return;
            }
        }

        OpenApiContext ctx = new OpenApiContext(requestId, key, owner, ip);
        String endUser = request.getHeader("X-End-User");
        ctx.setEndUser(endUser);
        CurrentActor.set(ctx.asActor());
        OpenApiContext.set(ctx);

        GuardrailPolicy policy = policyService.getEffectivePolicy(owner.getId());
        ctx.setPolicy(policy);

        if (policy != null && Boolean.TRUE.equals(policy.getKillSwitch())) {
            write(response, 403, "kill_switch", "该开发者的开放调用已被紧急停用", requestId);
            cleanup();
            return;
        }

        if (policy != null && !policyService.isWithinAllowedHours(policy)) {
            write(response, 403, "outside_allowed_hours", "当前时间不在允许的调用时段内 (" + policy.getAllowedHours() + ")", requestId);
            cleanup();
            return;
        }

        String clientType = headerOr(request, "X-Client-Type", "CUSTOM_KEY");
        String clientId = request.getHeader("X-Client-Id");
        String clientPolicy = policy != null ? policy.getClientPolicy() : "OFF";
        if (!"OFF".equalsIgnoreCase(clientPolicy)) {
            if (clientId == null || clientId.isBlank()) {
                if ("ENFORCE".equalsIgnoreCase(clientPolicy) || "ENFORCE_AUTO_REGISTER".equalsIgnoreCase(clientPolicy)) {
                    write(response, 403, "client_required", "请提供 X-Client-Id", requestId);
                    cleanup();
                    return;
                } else if ("LOG_ONLY".equalsIgnoreCase(clientPolicy)) {
                    auditRecorder.record("client.unknown", "CLIENT", "NONE", "WARNING", "CLIENT_UNKNOWN", "LOW", "LOG_ONLY 模式检测到缺失 X-Client-Id");
                }
            } else {
                String normalized = normalizeClientId(clientType, clientId);
                String hash = OpenApiKeyService.sha256(normalized);
                Optional<ClientCredential> existing = clientRepository.findByOwnerIdAndClientTypeAndClientIdHash(
                        owner.getId(), clientType.toUpperCase(), hash);
                if (existing.isPresent()) {
                    ClientCredential client = existing.get();
                    if (client.getExpiresAt() != null && client.getExpiresAt().isBefore(LocalDateTime.now())) {
                        if ("ENFORCE".equalsIgnoreCase(clientPolicy) || "ENFORCE_AUTO_REGISTER".equalsIgnoreCase(clientPolicy)) {
                            write(response, 403, "client_expired", "接入终端授权已过期", requestId);
                            cleanup();
                            return;
                        } else if ("LOG_ONLY".equalsIgnoreCase(clientPolicy)) {
                            auditRecorder.record("client.expired", "CLIENT", client.getClientId(), "WARNING", "CLIENT_EXPIRED", "LOW", "LOG_ONLY 模式检测到已过期终端");
                        }
                    } else if (!"ACTIVE".equalsIgnoreCase(client.getStatus())) {
                        if ("ENFORCE".equalsIgnoreCase(clientPolicy) || "ENFORCE_AUTO_REGISTER".equalsIgnoreCase(clientPolicy)) {
                            write(response, 403, "client_not_allowed", "终端未批准或已停用", requestId);
                            cleanup();
                            return;
                        } else if ("LOG_ONLY".equalsIgnoreCase(clientPolicy)) {
                            auditRecorder.record("client.inactive", "CLIENT", client.getClientId(), "WARNING", "CLIENT_INACTIVE", "LOW", "LOG_ONLY 模式检测到未启用终端: " + client.getStatus());
                        }
                    } else {
                        client.setLastSeenAt(LocalDateTime.now());
                        client.setLastSeenIp(ip);
                        clientRepository.save(client);
                        ctx.setClient(client);
                    }
                } else if ("ENFORCE".equalsIgnoreCase(clientPolicy)) {
                    write(response, 403, "client_not_allowed", "终端未在白名单", requestId);
                    cleanup();
                    return;
                } else if ("ENFORCE_AUTO_REGISTER".equalsIgnoreCase(clientPolicy)) {
                    ClientCredential pending = new ClientCredential();
                    pending.setOwnerId(owner.getId());
                    pending.setClientType(clientType.toUpperCase());
                    pending.setClientId(maskClientId(clientType, normalized));
                    pending.setClientIdHash(hash);
                    pending.setStatus("PENDING");
                    pending.setLastSeenIp(ip);
                    clientRepository.save(pending);
                    auditRecorder.record("client.pending", "CLIENT", pending.getClientId(), "WARNING", "AUTO_REGISTER", "LOW", "终端自动登记为待审批");
                    write(response, 403, "client_not_allowed", "终端已登记为待审批", requestId);
                    cleanup();
                    return;
                } else if ("LOG_ONLY".equalsIgnoreCase(clientPolicy)) {
                    auditRecorder.record("client.unknown", "CLIENT", clientId, "WARNING", "CLIENT_UNKNOWN", "LOW", "LOG_ONLY 模式检测到未登记终端: " + clientId);
                }
            }
        }

        try {
            if (!resolved.get().isLegacy()) {
                openApiKeyService.touchUsage(key, ip);
            }
            filterChain.doFilter(request, response);
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        OpenApiContext.clear();
        CurrentActor.clear();
    }

    private String extractApiKey(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        String custom = request.getHeader("X-API-Key");
        return custom != null && !custom.isBlank() ? custom.trim() : null;
    }

    private void write(HttpServletResponse response, int status, String code, String message, String requestId) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("request_id", requestId);
        body.put("data", data);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static boolean ipMatches(String ip, String rule) {
        if (ip == null || rule == null) {
            return false;
        }
        String trimmed = rule.trim();
        if (trimmed.contains("/")) {
            return ip.startsWith(trimmed.substring(0, trimmed.indexOf('/')));
        }
        return ip.equals(trimmed);
    }

    private static String headerOr(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static String normalizeClientId(String type, String raw) {
        String value = raw.trim();
        if ("MAC".equalsIgnoreCase(type)) {
            return value.replaceAll("[^A-Fa-f0-9]", "").toUpperCase();
        }
        return value;
    }

    private static String maskClientId(String type, String normalized) {
        if ("CUSTOM_KEY".equalsIgnoreCase(type) && normalized.length() > 8) {
            return normalized.substring(0, 4) + "****" + normalized.substring(normalized.length() - 4);
        }
        if ("MAC".equalsIgnoreCase(type) && normalized.length() == 12) {
            return normalized.replaceAll("(.{2})", "$1:").substring(0, 17);
        }
        return normalized;
    }

    @SuppressWarnings("unused")
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return value;
        }
    }
}
