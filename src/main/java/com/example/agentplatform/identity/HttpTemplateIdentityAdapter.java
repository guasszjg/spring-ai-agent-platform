package com.example.agentplatform.identity;

import com.example.agentplatform.config.OutboundUrlValidator;
import com.example.agentplatform.config.SecretCrypto;
import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.ExternalIdentity;
import com.example.agentplatform.model.IdentityProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;

@Component
public class HttpTemplateIdentityAdapter implements IdentityProviderAdapter {

    private final OutboundUrlValidator urlValidator;
    private final SecretCrypto secretCrypto;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public HttpTemplateIdentityAdapter(OutboundUrlValidator urlValidator, SecretCrypto secretCrypto, ObjectMapper objectMapper) {
        this.urlValidator = urlValidator;
        this.secretCrypto = secretCrypto;
        this.objectMapper = objectMapper;
    }

    @Override
    public IdentitySyncResult createRemote(AppUser user, IdentityProvider provider) {
        return invoke("create_account", user, provider, null);
    }

    @Override
    public IdentitySyncResult bind(AppUser user, IdentityProvider provider, String externalId) {
        return invoke("bind_account", user, provider, externalId);
    }

    @Override
    public IdentitySyncResult unbind(ExternalIdentity identity, IdentityProvider provider) {
        AppUser user = new AppUser();
        user.setId(identity.getUserId());
        return invoke("unbind_account", user, provider, identity.getExternalId());
    }

    @Override
    public IdentitySyncResult query(ExternalIdentity identity, IdentityProvider provider) {
        AppUser user = new AppUser();
        user.setId(identity.getUserId());
        return invoke("query_account", user, provider, identity.getExternalId());
    }

    @Override
    public IdentitySyncResult suspend(ExternalIdentity identity, IdentityProvider provider) {
        AppUser user = new AppUser();
        user.setId(identity.getUserId());
        return invoke("suspend_account", user, provider, identity.getExternalId());
    }

    public IdentitySyncResult probe(IdentityProvider provider) {
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            return IdentitySyncResult.fail("未配置 Base URL");
        }
        try {
            urlValidator.validateProviderBaseUrl(provider.getBaseUrl());
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(provider.getBaseUrl())))
                    .timeout(Duration.ofMillis(provider.getTimeoutMs()))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                return IdentitySyncResult.fail("探测失败 HTTP " + response.statusCode());
            }
            return IdentitySyncResult.ok(null, provider.getName(), null);
        } catch (Exception e) {
            return IdentitySyncResult.fail(e.getMessage());
        }
    }

    private IdentitySyncResult invoke(String operation, AppUser user, IdentityProvider provider, String externalId) {
        try {
            JsonNode ops = parseJson(provider.getOperations());
            JsonNode spec = ops.path(operation);
            if (spec.isMissingNode() || spec.isNull()) {
                return IdentitySyncResult.fail("未配置操作模板: " + operation);
            }
            String path = spec.path("path").asText("");
            String method = spec.path("method").asText("POST");
            if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
                return IdentitySyncResult.fail("未配置对方 Base URL");
            }
            urlValidator.validateProviderBaseUrl(provider.getBaseUrl());
            String renderedPath = render(path, user, provider, externalId);
            String url = trimSlash(provider.getBaseUrl()) + (renderedPath.startsWith("/") ? renderedPath : "/" + renderedPath);
            urlValidator.validateProviderBaseUrl(url.contains("://") ? url : provider.getBaseUrl());

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(provider.getTimeoutMs()));
            JsonNode headers = spec.path("headers");
            if (headers.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = headers.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    builder.header(entry.getKey(), render(entry.getValue().asText(), user, provider, externalId));
                }
            }
            applyAuth(builder, provider);
            String body = spec.has("body") ? render(objectMapper.writeValueAsString(spec.get("body")), user, provider, externalId) : "";
            if ("GET".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
                builder.method(method.toUpperCase(), HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json");
                builder.method(method.toUpperCase(), HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                return IdentitySyncResult.fail("对方返回 HTTP " + response.statusCode());
            }
            return mapResponse(response.body(), provider, externalId);
        } catch (Exception e) {
            return IdentitySyncResult.fail(e.getMessage() != null ? e.getMessage() : "调用对方接口失败");
        }
    }

    private IdentitySyncResult mapResponse(String body, IdentityProvider provider, String fallbackExternalId) {
        try {
            JsonNode root = body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
            JsonNode mapping = parseJson(provider.getFieldMapping());
            String externalId = readMapped(root, mapping, "external_id", fallbackExternalId);
            String displayName = readMapped(root, mapping, "display_name", null);
            JsonNode attrsNode = mapping.has("attributes") ? readPath(root, mapping.path("attributes").asText()) : root;
            String attributes = attrsNode != null ? objectMapper.writeValueAsString(attrsNode) : body;
            if (externalId == null || externalId.isBlank()) {
                return IdentitySyncResult.fail("对方响应中未解析到 external_id，请检查字段映射");
            }
            return IdentitySyncResult.ok(externalId, displayName, attributes);
        } catch (Exception e) {
            if (fallbackExternalId != null && !fallbackExternalId.isBlank()) {
                return IdentitySyncResult.ok(fallbackExternalId, null, body);
            }
            return IdentitySyncResult.fail("无法解析对方响应");
        }
    }

    private String readMapped(JsonNode root, JsonNode mapping, String key, String fallback) {
        if (!mapping.has(key)) {
            if (root.has(key)) {
                return root.path(key).asText(fallback);
            }
            JsonNode data = root.path("data");
            if (data.has(key)) {
                return data.path(key).asText(fallback);
            }
            return fallback;
        }
        JsonNode node = readPath(root, mapping.path(key).asText());
        return node == null || node.isMissingNode() || node.isNull() ? fallback : node.asText(fallback);
    }

    private JsonNode readPath(JsonNode root, String path) {
        if (path == null || path.isBlank() || "$".equals(path)) {
            return root;
        }
        String[] parts = path.replace("$.", "").split("\\.");
        JsonNode current = root;
        for (String part : parts) {
            if (part.isBlank()) continue;
            current = current.path(part);
        }
        return current;
    }

    private void applyAuth(HttpRequest.Builder builder, IdentityProvider provider) {
        String type = provider.getAuthType() != null ? provider.getAuthType() : "NONE";
        String credential = secretCrypto.decrypt(provider.getCredentialEncrypted());
        if (credential == null || credential.isBlank() || "NONE".equalsIgnoreCase(type)) {
            return;
        }
        if ("BEARER".equalsIgnoreCase(type)) {
            builder.header("Authorization", "Bearer " + credential);
        } else if ("HEADER".equalsIgnoreCase(type)) {
            builder.header("X-Api-Key", credential);
        }
    }

    private String render(String template, AppUser user, IdentityProvider provider, String externalId) {
        if (template == null) {
            return "";
        }
        return template
                .replace("{{user.id}}", nullToEmpty(user.getId()))
                .replace("{{user.username}}", nullToEmpty(user.getUsername()))
                .replace("{{user.nickname}}", nullToEmpty(user.getNickname()))
                .replace("{{external_id}}", nullToEmpty(externalId))
                .replace("{{provider.code}}", nullToEmpty(provider.getCode()));
    }

    private JsonNode parseJson(String raw) {
        try {
            return objectMapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private static String trimSlash(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
