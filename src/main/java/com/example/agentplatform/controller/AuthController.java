package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.LoginRequest;
import com.example.agentplatform.model.LoginResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String SESSION_USER = "LOGGED_IN_USER";

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request, HttpSession session) {
        if (request.getUsername() == null || request.getUsername().trim().isEmpty() ||
            request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            return ApiResponse.error("用户名和密码不能为空");
        }

        String username = request.getUsername().trim();
        String password = request.getPassword().trim();

        // 演示系统：支持 admin / 123456 或 admin / admin123 或任意非空账号
        if ("admin".equalsIgnoreCase(username) || "demo".equalsIgnoreCase(username) || password.length() >= 6) {
            String token = "agt-token-" + UUID.randomUUID().toString().replace("-", "");
            String nickname = "admin".equalsIgnoreCase(username) ? "超级管理员" : "智能体工程师 (" + username + ")";
            String role = "admin".equalsIgnoreCase(username) ? "System Admin" : "Agent Developer";
            String avatar = "https://api.dicebear.com/7.x/bottts/svg?seed=" + username;

            LoginResponse response = new LoginResponse(token, username, nickname, role, avatar);
            session.setAttribute(SESSION_USER, response);

            return ApiResponse.ok("登录成功，欢迎回来！", response);
        } else {
            return ApiResponse.error("用户名或密码错误（演示账号：admin / admin123）");
        }
    }

    @GetMapping("/current")
    public ApiResponse<LoginResponse> getCurrentUser(HttpSession session) {
        LoginResponse user = (LoginResponse) session.getAttribute(SESSION_USER);
        if (user != null) {
            return ApiResponse.ok(user);
        }
        // 如果未存 session，返回默认体验账号
        return ApiResponse.ok(new LoginResponse(
                "agt-guest-token",
                "admin",
                "超级管理员",
                "System Admin",
                "https://api.dicebear.com/7.x/bottts/svg?seed=admin"
        ));
    }

    @PostMapping("/logout")
    public ApiResponse<String> logout(HttpSession session) {
        session.removeAttribute(SESSION_USER);
        session.invalidate();
        return ApiResponse.ok("已安全退出登录", null);
    }
}
