package com.example.agentplatform.model;

import java.time.LocalDateTime;

public class UserSummaryDto {
    private String id;
    private String username;
    private String nickname;
    private String role;
    private String roleName;
    private String status;
    private Boolean mustChangePassword;
    private Integer authVersion;
    private String avatar;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long keyCount = 0L;
    private Long clientCount = 0L;
    private Long todayCalls = 0L;

    public UserSummaryDto() {
    }

    public UserSummaryDto(AppUser user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.nickname = user.getNickname();
        UserRole userRole = UserRole.fromRaw(user.getRole());
        this.role = userRole.getCode();
        this.roleName = userRole.getName();
        this.status = user.getStatus();
        this.mustChangePassword = user.getMustChangePassword();
        this.authVersion = user.getAuthVersion();
        this.avatar = user.getAvatar();
        this.createdAt = user.getCreatedAt();
        this.updatedAt = user.getUpdatedAt();
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

    public Boolean getMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(Boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public Integer getAuthVersion() {
        return authVersion;
    }

    public void setAuthVersion(Integer authVersion) {
        this.authVersion = authVersion;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getKeyCount() { return keyCount != null ? keyCount : 0L; }
    public void setKeyCount(Long keyCount) { this.keyCount = keyCount; }
    public Long getClientCount() { return clientCount != null ? clientCount : 0L; }
    public void setClientCount(Long clientCount) { this.clientCount = clientCount; }
    public Long getTodayCalls() { return todayCalls != null ? todayCalls : 0L; }
    public void setTodayCalls(Long todayCalls) { this.todayCalls = todayCalls; }
}