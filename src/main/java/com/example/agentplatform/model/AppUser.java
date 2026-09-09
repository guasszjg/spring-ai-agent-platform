package com.example.agentplatform.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @JsonIgnore
    @Column(nullable = false, length = 200)
    private String password;

    @Column(length = 100)
    private String nickname;

    @Column(length = 64)
    private String role = UserRole.DEVELOPER.getCode();

    @Column(length = 32)
    private String status = UserStatus.ACTIVE;

    @Column(name = "auth_version")
    private Integer authVersion = 1;

    @Column(name = "must_change_password")
    private Boolean mustChangePassword = false;

    @Column(name = "temp_password_expires_at")
    private LocalDateTime tempPasswordExpiresAt;

    @Column(length = 500)
    private String avatar;

    @Column(name = "ui_preferences", columnDefinition = "TEXT")
    private String uiPreferences;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public AppUser() {
    }

    @PrePersist
    public void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.updatedAt == null) {
            this.updatedAt = LocalDateTime.now();
        }
        if (this.authVersion == null) {
            this.authVersion = 1;
        }
        if (this.status == null) {
            this.status = UserStatus.ACTIVE;
        }
        if (this.mustChangePassword == null) {
            this.mustChangePassword = false;
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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

    public String getStatus() {
        return status != null ? status : UserStatus.ACTIVE;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getAuthVersion() {
        return authVersion != null ? authVersion : 1;
    }

    public void setAuthVersion(Integer authVersion) {
        this.authVersion = authVersion;
    }

    public Boolean getMustChangePassword() {
        return mustChangePassword != null && mustChangePassword;
    }

    public void setMustChangePassword(Boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public LocalDateTime getTempPasswordExpiresAt() {
        return tempPasswordExpiresAt;
    }

    public void setTempPasswordExpiresAt(LocalDateTime tempPasswordExpiresAt) {
        this.tempPasswordExpiresAt = tempPasswordExpiresAt;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getUiPreferences() {
        return uiPreferences;
    }

    public void setUiPreferences(String uiPreferences) {
        this.uiPreferences = uiPreferences;
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
}
