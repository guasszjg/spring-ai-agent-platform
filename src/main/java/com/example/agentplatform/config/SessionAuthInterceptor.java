package com.example.agentplatform.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

@Component
public class SessionAuthInterceptor implements HandlerInterceptor {

    public static final String SESSION_USER = "LOGGED_IN_USER";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                || request.getSession(false) != null
                && request.getSession(false).getAttribute(SESSION_USER) != null) {
            return true;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"message\":\"登录已失效，请重新登录\",\"data\":null}");
        return false;
    }
}
