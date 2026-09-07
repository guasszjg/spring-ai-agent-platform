package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.LoginRequest;
import com.example.agentplatform.model.LoginResponse;
import com.example.agentplatform.config.SessionAuthInterceptor;
import com.example.agentplatform.service.AuthService;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest request,
                                                             HttpServletRequest servletRequest,
                                                             HttpSession session) {
        if (request.getUsername() == null || request.getUsername().trim().isEmpty() ||
            request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("用户名和密码不能为空"));
        }

        return authService.login(request.getUsername().trim(), request.getPassword())
                .map(response -> {
                    servletRequest.changeSessionId();
                    session.setAttribute(SessionAuthInterceptor.SESSION_USER, response);
                    return ResponseEntity.ok(ApiResponse.ok("登录成功，欢迎回来！", response));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.error("用户名或密码错误")));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<LoginResponse>> getCurrentUser(HttpSession session) {
        LoginResponse user = (LoginResponse) session.getAttribute(SessionAuthInterceptor.SESSION_USER);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("未登录"));
        }
        LoginResponse latest = authService.currentUser(user.getUsername()).orElse(user);
        session.setAttribute(SessionAuthInterceptor.SESSION_USER, latest);
        return ResponseEntity.ok(ApiResponse.ok(latest));
    }

    @GetMapping("/preferences")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPreferences(HttpSession session) {
        LoginResponse user = (LoginResponse) session.getAttribute(SessionAuthInterceptor.SESSION_USER);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("未登录"));
        }
        return ResponseEntity.ok(ApiResponse.ok(authService.getPreferences(user.getUsername())));
    }

    @PutMapping("/preferences")
    public ResponseEntity<ApiResponse<Map<String, Object>>> savePreferences(
            @RequestBody Map<String, Object> preferences,
            HttpSession session) {
        LoginResponse user = (LoginResponse) session.getAttribute(SessionAuthInterceptor.SESSION_USER);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("未登录"));
        }
        try {
            Map<String, Object> saved = authService.savePreferences(user.getUsername(), preferences);
            user.setPreferences(saved);
            session.setAttribute(SessionAuthInterceptor.SESSION_USER, user);
            return ResponseEntity.ok(ApiResponse.ok("界面偏好已保存", saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/session")
    public ResponseEntity<ApiResponse<Void>> logout(HttpSession session) {
        session.removeAttribute(SessionAuthInterceptor.SESSION_USER);
        session.invalidate();
        return ResponseEntity.ok(ApiResponse.ok("已安全退出登录", null));
    }
}
