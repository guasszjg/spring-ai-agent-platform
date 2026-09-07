package com.example.agentplatform.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class LoginResponse {
    private String username;
    private String nickname;
    private String role;
    private String avatar;
    private Map<String, Object> preferences = new LinkedHashMap<>();

    public LoginResponse() {
    }

    public LoginResponse(String username, String nickname, String role, String avatar) {
        this(username, nickname, role, avatar, Map.of());
    }

    public LoginResponse(String username, String nickname, String role, String avatar, Map<String, Object> preferences) {
        this.username = username;
        this.nickname = nickname;
        this.role = role;
        this.avatar = avatar;
        this.preferences = preferences != null ? new LinkedHashMap<>(preferences) : new LinkedHashMap<>();
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

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public Map<String, Object> getPreferences() {
        return preferences;
    }

    public void setPreferences(Map<String, Object> preferences) {
        this.preferences = preferences != null ? new LinkedHashMap<>(preferences) : new LinkedHashMap<>();
    }
}
