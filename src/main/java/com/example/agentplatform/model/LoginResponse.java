package com.example.agentplatform.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class LoginResponse {
    private String id;
    private String username;
    private String nickname;
    private String role;
    private String roleName;
    private String status;
    private Integer authVersion = 1;
    private Boolean mustChangePassword = false;
    private String avatar;
    private Set<String> permissions = new LinkedHashSet<>();
    private Map<String, Object> preferences = new LinkedHashMap<>();
    private String csrfToken;

    public LoginResponse() {
    }

    public LoginResponse(String username, String nickname, String role, String avatar) {
        this(null, username, nickname, role, avatar, Map.of());
    }

    public LoginResponse(String id, String username, String nickname, String role, String avatar, Map<String, Object> preferences) {
        this.id = id;
        this.username = username;
        this.nickname = nickname;
        UserRole userRole = UserRole.fromRaw(role);
        this.role = userRole.getCode();
        this.roleName = userRole.getName();
        this.avatar = avatar;
        this.permissions = userRole.getPermissions();
        this.status = UserStatus.ACTIVE;
        this.authVersion = 1;
        this.mustChangePassword = false;
        this.preferences = preferences != null ? new LinkedHashMap<>(preferences) : new LinkedHashMap<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getAuthVersion() {
        return authVersion;
    }

    public void setAuthVersion(Integer authVersion) {
        this.authVersion = authVersion;
    }

    public Boolean getMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(Boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<String> permissions) {
        this.permissions = permissions != null ? new LinkedHashSet<>(permissions) : new LinkedHashSet<>();
    }

    public Map<String, Object> getPreferences() {
        return preferences;
    }

    public void setPreferences(Map<String, Object> preferences) {
        this.preferences = preferences != null ? new LinkedHashMap<>(preferences) : new LinkedHashMap<>();
    }

    public String getCsrfToken() {
        return csrfToken;
    }

    public void setCsrfToken(String csrfToken) {
        this.csrfToken = csrfToken;
    }
}
