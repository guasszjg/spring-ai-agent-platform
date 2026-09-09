package com.example.agentplatform.config;

import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.LoginResponse;
import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.model.UserStatus;
import com.example.agentplatform.repository.UserRepository;
import com.example.agentplatform.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public class SessionAuthInterceptor implements HandlerInterceptor {

    public static final String SESSION_USER = "LOGGED_IN_USER";

    public static final String CSRF_TOKEN_ATTR = "CSRF_TOKEN";

    private static final Set<String> ALLOWED_WHEN_MUST_CHANGE_PASSWORD = Set.of(
            "/api/auth/me",
            "/api/auth/password",
            "/api/auth/session",
            "/api/auth/preferences"
    );

    private final UserRepository userRepository;

    public SessionAuthInterceptor() {
        this.userRepository = null;
    }

    @Autowired
    public SessionAuthInterceptor(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String uri = request.getRequestURI();
        boolean isLogout = uri != null && (uri.endsWith("/api/auth/session") || uri.endsWith("/api/auth/logout"));
        if (isLogout) {
            return true;
        }

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(SESSION_USER) == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "登录已失效，请重新登录");
            return false;
        }

        String method = request.getMethod();
        boolean isMutating = "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);

        String sessionCsrf = (String) session.getAttribute(CSRF_TOKEN_ATTR);
        if (sessionCsrf == null) {
            sessionCsrf = java.util.UUID.randomUUID().toString().replace("-", "");
            session.setAttribute(CSRF_TOKEN_ATTR, sessionCsrf);
        }

        if (isMutating) {
            String csrfHeader = request.getHeader("X-CSRF-TOKEN");
            if (csrfHeader == null || csrfHeader.isBlank()) {
                csrfHeader = request.getHeader("X-XSRF-TOKEN");
            }
            if (csrfHeader == null || !sessionCsrf.equalsIgnoreCase(csrfHeader.trim())) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "CSRF 凭证校验失败，请刷新页面重试");
                return false;
            }
        }

        Object sessionUserObj = session.getAttribute(SESSION_USER);
        if (!(sessionUserObj instanceof LoginResponse sessionUser)) {
            return true;
        }

        if (userRepository != null) {
            AppUser dbUser = userRepository.findByUsernameIgnoreCase(sessionUser.getUsername()).orElse(null);
            if (dbUser == null) {
                session.removeAttribute(SESSION_USER);
                session.invalidate();
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "用户账号不存在");
                return false;
            }

            if (UserStatus.DISABLED.equals(dbUser.getStatus())) {
                session.removeAttribute(SESSION_USER);
                session.invalidate();
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "账号已被管理员停用，已安全退出");
                return false;
            }

            Integer dbVersion = dbUser.getAuthVersion() != null ? dbUser.getAuthVersion() : 1;
            Integer sessVersion = sessionUser.getAuthVersion() != null ? sessionUser.getAuthVersion() : 1;
            if (!dbVersion.equals(sessVersion)) {
                session.removeAttribute(SESSION_USER);
                session.invalidate();
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "账号安全凭据或权限已更新，请重新登录");
                return false;
            }

            boolean mustChangePassword = Boolean.TRUE.equals(dbUser.getMustChangePassword());
            if (mustChangePassword && dbUser.getTempPasswordExpiresAt() != null
                    && java.time.LocalDateTime.now().isAfter(dbUser.getTempPasswordExpiresAt())) {
                session.removeAttribute(SESSION_USER);
                session.invalidate();
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "临时密码已过期，请联系管理员重新重置密码");
                return false;
            }

            if (mustChangePassword && !ALLOWED_WHEN_MUST_CHANGE_PASSWORD.contains(uri)) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "您的账号处于初始/重置状态，请先设置新密码以激活账号");
                return false;
            }

            UserRole role = UserRole.fromRaw(dbUser.getRole());
            CurrentActor.set(new CurrentActor(
                    dbUser.getId(),
                    dbUser.getUsername(),
                    dbUser.getNickname(),
                    role,
                    dbVersion,
                    dbUser.getStatus(),
                    mustChangePassword
            ));
        } else {
            UserRole role = UserRole.fromRaw(sessionUser.getRole());
            CurrentActor.set(new CurrentActor(
                    sessionUser.getId(),
                    sessionUser.getUsername(),
                    sessionUser.getNickname(),
                    role,
                    sessionUser.getAuthVersion(),
                    sessionUser.getStatus(),
                    Boolean.TRUE.equals(sessionUser.getMustChangePassword())
            ));
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        CurrentActor.clear();
    }

    private void writeError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(String.format("{\"success\":false,\"message\":\"%s\",\"data\":null}", message));
    }
}
