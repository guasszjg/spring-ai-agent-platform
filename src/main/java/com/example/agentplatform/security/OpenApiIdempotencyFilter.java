package com.example.agentplatform.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class OpenApiIdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OpenApiIdempotencyFilter.class);

    public record CachedResponse(int status, String contentType, byte[] body) {}

    private final Cache<String, CachedResponse> cache;

    public OpenApiIdempotencyFilter() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(24, TimeUnit.HOURS)
                .maximumSize(20000)
                .build();
    }

    public OpenApiIdempotencyFilter(Cache<String, CachedResponse> customCache) {
        this.cache = customCache;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String method = request.getMethod();
        // 幂等防抖主要针对写操作（POST / PUT / DELETE）
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        idempotencyKey = idempotencyKey.trim();
        if (idempotencyKey.length() > 128) {
            idempotencyKey = idempotencyKey.substring(0, 128);
        }

        OpenApiContext ctx = OpenApiContext.get();
        String ownerId = (ctx != null && ctx.getOwner() != null) ? ctx.getOwner().getId() : "anon";
        String cacheKey = ownerId + ":" + method.toUpperCase() + ":" + request.getRequestURI() + ":" + idempotencyKey;

        CachedResponse cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("Idempotent request replayed for key: {}", cacheKey);
            response.setStatus(cached.status());
            if (cached.contentType() != null) {
                response.setContentType(cached.contentType());
            }
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("X-Idempotent-Replay", "true");
            if (ctx != null && ctx.getRequestId() != null) {
                response.setHeader("X-Request-Id", ctx.getRequestId());
            }
            response.getOutputStream().write(cached.body());
            response.getOutputStream().flush();
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, wrapper);
        } finally {
            int status = wrapper.getStatus();
            // 仅对非 5xx 服务端故障结果进行幂等缓存（5xx 允许客户端网络重试）
            if (status > 0 && status < 500) {
                byte[] body = wrapper.getContentAsByteArray();
                cache.put(cacheKey, new CachedResponse(status, wrapper.getContentType(), body));
            }
            wrapper.copyBodyToResponse();
        }
    }

    public Cache<String, CachedResponse> getCache() {
        return cache;
    }
}
