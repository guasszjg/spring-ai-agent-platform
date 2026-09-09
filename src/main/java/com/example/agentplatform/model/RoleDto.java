package com.example.agentplatform.model;

import java.util.Set;

public class RoleDto {
    private String code;
    private String name;
    private String description;
    private boolean builtin;
    private long userCount;
    private Set<String> permissions;

    public RoleDto() {
    }

    public RoleDto(String code, String name, String description, boolean builtin, long userCount, Set<String> permissions) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.builtin = builtin;
        this.userCount = userCount;
        this.permissions = permissions;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isBuiltin() {
        return builtin;
    }

    public void setBuiltin(boolean builtin) {
        this.builtin = builtin;
    }

    public long getUserCount() {
        return userCount;
    }

    public void setUserCount(long userCount) {
        this.userCount = userCount;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<String> permissions) {
        this.permissions = permissions;
    }
}
