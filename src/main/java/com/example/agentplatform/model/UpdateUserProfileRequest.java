package com.example.agentplatform.model;

public class UpdateUserProfileRequest {
    private String nickname;
    private String avatar;

    public UpdateUserProfileRequest() {
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }
}
