package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.LoginRequest;
import com.example.agentplatform.model.LoginResponse;
import com.example.agentplatform.service.AuthService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String SESSION_USER = "LOGGED_IN_USER";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest request, HttpSession session) {
        if (request.getUsername() == null || request.getUsername().trim().isEmpty() ||
            request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("用户名和密码不能为空"));
        }

        return authService.login(request.getUsername().trim(), request.getPassword())
                .map(response -> {
                    session.setAttribute(SESSION_USER, response);
                    return ResponseEntity.ok(ApiResponse.ok("登录成功，欢迎回来！", response));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.error("用户名或密码错误")));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<LoginResponse>> getCurrentUser(HttpSession session) {
        LoginResponse user = (LoginResponse) session.getAttribute(SESSION_USER);
        if (user != null) {
            return ResponseEntity.ok(ApiResponse.ok(user));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("未登录"));
    }

    @DeleteMapping("/session")
    public ResponseEntity<ApiResponse<Void>> logout(HttpSession session) {
        session.removeAttribute(SESSION_USER);
        session.invalidate();
        return ResponseEntity.ok(ApiResponse.ok("已安全退出登录", null));
    }
}
