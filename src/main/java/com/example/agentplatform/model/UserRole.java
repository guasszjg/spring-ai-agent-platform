package com.example.agentplatform.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public enum UserRole {
    SUPER_ADMIN("SUPER_ADMIN", "超级管理员", "拥有全平台系统治理、模型网关、用户管理与资源调度最高权限"),
    DEVELOPER("DEVELOPER", "开发者", "可创建、调试并管理属于自己的智能体、知识库与场景模板"),
    VIEWER("VIEWER", "只读观察员", "仅可查看被授权智能体与知识库的元数据与监控指标，无编辑与调试权限");

    private final String code;
    private final String name;
    private final String description;

    UserRole(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Set<String> getPermissions() {
        Set<String> set = new LinkedHashSet<>();
        switch (this) {
            case SUPER_ADMIN -> {
                // 用户与角色治理
                set.add("user:read");
                set.add("user:create");
                set.add("user:update");
                set.add("user:assign-role");
                set.add("user:change-status");
                set.add("user:reset-password");
                set.add("user:transfer-resources");
                set.add("role:read");
                set.add("audit:read");

                // 网关与渠道治理
                set.add("gateway:manage");
                set.add("model:use");

                // 智能体全权操作
                set.add("agent:create");
                set.add("agent:view");
                set.add("agent:config:read");
                set.add("agent:update");
                set.add("agent:delete");
                set.add("agent:copy");
                set.add("agent:status:update");
                set.add("agent:run");
                set.add("agent:logs:read");
                set.add("agent:metrics:read");
                set.add("agent:key:manage");

                // 知识库全权操作
                set.add("knowledge:create");
                set.add("knowledge:view");
                set.add("knowledge:content:read");
                set.add("knowledge:update");
                set.add("knowledge:delete");
                set.add("knowledge:use");
                set.add("knowledge:sync");

                // 模板全权操作
                set.add("template:create");
                set.add("template:view");
                set.add("template:use");
                set.add("template:update");
                set.add("template:delete");
                set.add("template:publish");

                // 工具
                set.add("tool:use");
                set.add("tool:credential:manage");
                set.add("resource:share");
            }
            case DEVELOPER -> {
                set.add("agent:create");
                set.add("agent:view");
                set.add("agent:config:read");
                set.add("agent:update");
                set.add("agent:delete");
                set.add("agent:copy");
                set.add("agent:status:update");
                set.add("agent:run");
                set.add("agent:logs:read");
                set.add("agent:metrics:read");
                set.add("agent:key:manage");

                set.add("knowledge:create");
                set.add("knowledge:view");
                set.add("knowledge:content:read");
                set.add("knowledge:update");
                set.add("knowledge:delete");
                set.add("knowledge:use");

                set.add("template:create");
                set.add("template:view");
                set.add("template:use");
                set.add("template:update");
                set.add("template:delete");

                set.add("model:use");
                set.add("tool:use");
                set.add("resource:share");
            }
            case VIEWER -> {
                set.add("agent:view");
                set.add("agent:metrics:read");
                set.add("knowledge:view");
                set.add("template:view");
            }
        }
        return Collections.unmodifiableSet(set);
    }

    public static UserRole fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEVELOPER;
        }
        String upper = raw.trim().toUpperCase();
        if (upper.contains("ADMIN") || upper.contains("SUPER")) {
            return SUPER_ADMIN;
        }
        if (upper.contains("VIEWER") || upper.contains("OBSERVER") || upper.contains("GUEST")) {
            return VIEWER;
        }
        return DEVELOPER;
    }
}
