package com.example.agentplatform.security;

import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.model.UserStatus;

public class CurrentActor {

    private static final ThreadLocal<CurrentActor> CURRENT = new ThreadLocal<>();

    private final String userId;
    private final String username;
    private final String nickname;
    private final UserRole role;
    private final Integer authVersion;
    private final String status;
    private final boolean mustChangePassword;

    public CurrentActor(String userId, String username, String nickname, UserRole role, Integer authVersion, String status, boolean mustChangePassword) {
        this.userId = userId;
        this.username = username;
        this.nickname = nickname;
        this.role = role != null ? role : UserRole.DEVELOPER;
        this.authVersion = authVersion != null ? authVersion : 1;
        this.status = status != null ? status : UserStatus.ACTIVE;
        this.mustChangePassword = mustChangePassword;
    }

    public CurrentActor(String userId, String username, UserRole role) {
        this(userId, username, username, role, 1, UserStatus.ACTIVE, false);
    }

    public static CurrentActor get() {
        return CURRENT.get();
    }

    public static void set(CurrentActor actor) {
        CURRENT.set(actor);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getNickname() {
        return nickname;
    }

    public UserRole getRole() {
        return role;
    }

    public Integer getAuthVersion() {
        return authVersion;
    }

    public String getStatus() {
        return status;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public boolean isSuperAdmin() {
        return role == UserRole.SUPER_ADMIN;
    }

    public boolean isDeveloper() {
        return role == UserRole.DEVELOPER;
    }

    public boolean isViewer() {
        return role == UserRole.VIEWER;
    }

    public boolean hasPermission(String permission) {
        return role != null && role.getPermissions().contains(permission);
    }
}
