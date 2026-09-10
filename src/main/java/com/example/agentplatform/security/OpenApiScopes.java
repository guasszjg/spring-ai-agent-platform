package com.example.agentplatform.security;

import com.example.agentplatform.model.UserRole;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OpenApiScopes {

    public static final String CHAT = "chat";
    public static final String AGENTS_READ = "agents:read";
    public static final String AGENTS_WRITE = "agents:write";
    public static final String KB_READ = "kb:read";
    public static final String KB_WRITE = "kb:write";
    public static final String AGENTS_BIND_KB = "agents:bind_kb";
    public static final String CONVERSATIONS_READ = "conversations:read";
    public static final String USAGE_READ = "usage:read";
    public static final String CLIENTS_WRITE = "clients:write";
    public static final String ACCOUNT_READ = "account:read";
    public static final String ACCOUNT_WRITE = "account:write";

    private static final Map<String, String> TO_PERMISSION = new LinkedHashMap<>();

    static {
        TO_PERMISSION.put(CHAT, "agent:run");
        TO_PERMISSION.put(AGENTS_READ, "agent:view");
        TO_PERMISSION.put(AGENTS_WRITE, "agent:update");
        TO_PERMISSION.put(KB_READ, "knowledge:view");
        TO_PERMISSION.put(KB_WRITE, "knowledge:update");
        TO_PERMISSION.put(AGENTS_BIND_KB, "knowledge:use");
        TO_PERMISSION.put(CONVERSATIONS_READ, "agent:logs:read");
        TO_PERMISSION.put(USAGE_READ, "agent:metrics:read");
        TO_PERMISSION.put(CLIENTS_WRITE, "agent:key:manage");
        TO_PERMISSION.put(ACCOUNT_READ, "agent:view");
        TO_PERMISSION.put(ACCOUNT_WRITE, "resource:share");
    }

    private OpenApiScopes() {}

    public static Set<String> all() {
        return TO_PERMISSION.keySet();
    }

    public static String requiredPermission(String scope) {
        return TO_PERMISSION.get(scope);
    }

    public static void validateAgainstRole(UserRole role, List<String> scopes) {
        if (role == null || role == UserRole.VIEWER) {
            throw new IllegalArgumentException("只读观察员不能签发开放凭证");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个 scope");
        }
        for (String scope : scopes) {
            String permission = TO_PERMISSION.get(scope);
            if (permission == null) {
                throw new IllegalArgumentException("不支持的 scope: " + scope);
            }
            if (!role.getPermissions().contains(permission)) {
                throw new IllegalArgumentException("当前角色无权授予 scope: " + scope);
            }
        }
    }
}
