package com.example.agentplatform.model;

public class LoginResponse {
    private String username;
    private String nickname;
    private String role;
    private String avatar;

    public LoginResponse() {
    }

    public LoginResponse(String username, String nickname, String role, String avatar) {
        this.username = username;
        this.nickname = nickname;
        this.role = role;
        this.avatar = avatar;
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
}
