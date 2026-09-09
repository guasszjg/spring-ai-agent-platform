package com.example.agentplatform.controller;

import com.example.agentplatform.config.SessionAuthInterceptor;
import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.ChangePasswordRequest;
import com.example.agentplatform.model.LoginRequest;
import com.example.agentplatform.model.LoginResponse;
import com.example.agentplatform.model.UpdateUserProfileRequest;
import com.example.agentplatform.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

        try {
            return authService.login(request.getUsername().trim(), request.getPassword())
                    .map(response -> {
                        servletRequest.changeSessionId();
                        String csrfToken = java.util.UUID.randomUUID().toString().replace("-", "");
                        session.setAttribute(SessionAuthInterceptor.CSRF_TOKEN_ATTR, csrfToken);
                        response.setCsrfToken(csrfToken);
                        session.setAttribute(SessionAuthInterceptor.SESSION_USER, response);
                        return ResponseEntity.ok(ApiResponse.ok("登录成功，欢迎回来！", response));
                    })
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(ApiResponse.error("用户名或密码错误")));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/csrf")
    public ResponseEntity<ApiResponse<Map<String, String>>> getCsrfToken(HttpSession session) {
        String csrfToken = (String) session.getAttribute(SessionAuthInterceptor.CSRF_TOKEN_ATTR);
        if (csrfToken == null) {
            csrfToken = java.util.UUID.randomUUID().toString().replace("-", "");
            session.setAttribute(SessionAuthInterceptor.CSRF_TOKEN_ATTR, csrfToken);
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of("csrfToken", csrfToken)));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<LoginResponse>> getCurrentUser(HttpSession session) {
        LoginResponse user = (LoginResponse) session.getAttribute(SessionAuthInterceptor.SESSION_USER);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("未登录"));
        }
        LoginResponse latest = authService.currentUser(user.getUsername()).orElse(null);
        if (latest == null) {
            session.removeAttribute(SessionAuthInterceptor.SESSION_USER);
            session.invalidate();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("用户不存在或已停用"));
        }
        String csrfToken = (String) session.getAttribute(SessionAuthInterceptor.CSRF_TOKEN_ATTR);
        if (csrfToken == null) {
            csrfToken = java.util.UUID.randomUUID().toString().replace("-", "");
            session.setAttribute(SessionAuthInterceptor.CSRF_TOKEN_ATTR, csrfToken);
        }
        latest.setCsrfToken(csrfToken);
        session.setAttribute(SessionAuthInterceptor.SESSION_USER, latest);
        return ResponseEntity.ok(ApiResponse.ok(latest));
    }

    @PutMapping("/password")
    public ResponseEntity<ApiResponse<LoginResponse>> changePassword(@RequestBody ChangePasswordRequest request,
                                                                      HttpSession session) {
        LoginResponse user = (LoginResponse) session.getAttribute(SessionAuthInterceptor.SESSION_USER);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("未登录"));
        }
        try {
            LoginResponse updated = authService.changePassword(user.getUsername(), request);
            session.setAttribute(SessionAuthInterceptor.SESSION_USER, updated);
            return ResponseEntity.ok(ApiResponse.ok("密码修改成功，安全设置已更新", updated));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<LoginResponse>> updateProfile(@RequestBody UpdateUserProfileRequest request,
                                                                     HttpSession session) {
        LoginResponse user = (LoginResponse) session.getAttribute(SessionAuthInterceptor.SESSION_USER);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("未登录"));
        }
        try {
            LoginResponse updated = authService.updateProfile(user.getUsername(), request);
            session.setAttribute(SessionAuthInterceptor.SESSION_USER, updated);
            return ResponseEntity.ok(ApiResponse.ok("个人信息已更新", updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
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

    @org.springframework.web.bind.annotation.RequestMapping(
            value = {"/session", "/logout"},
            method = {
                    org.springframework.web.bind.annotation.RequestMethod.DELETE,
                    org.springframework.web.bind.annotation.RequestMethod.POST,
                    org.springframework.web.bind.annotation.RequestMethod.GET
            }
    )
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request,
                                                     jakarta.servlet.http.HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.removeAttribute(SessionAuthInterceptor.SESSION_USER);
                session.removeAttribute(SessionAuthInterceptor.CSRF_TOKEN_ATTR);
                session.invalidate();
            } catch (Exception ignored) {
            }
        }
        com.example.agentplatform.security.CurrentActor.clear();

        jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie("JSESSIONID", "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);

        return ResponseEntity.ok(ApiResponse.ok("已安全退出登录", null));
    }
}
