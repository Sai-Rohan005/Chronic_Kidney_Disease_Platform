package com.ckd.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.filter.OncePerRequestFilter;

final class LoginRateLimitFilter extends OncePerRequestFilter {
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]); if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; return n;", Long.class);
    private final StringRedisTemplate redis;
    private final int limit, window;
    LoginRateLimitFilter(StringRedisTemplate redis, int limit, int window) {
        if (limit < 1 || window < 1) throw new IllegalArgumentException("Login rate limits must be positive");
        this.redis = redis; this.limit = limit; this.window = window;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !request.getRequestURI().equals(request.getContextPath() + "/auth/login");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Long count;
        try { count = redis.execute(COUNT, List.of("auth:login:" + request.getRemoteAddr()), String.valueOf(window)); }
        catch (DataAccessException exception) { SecurityErrors.write(request, response, 503, "Login is temporarily unavailable"); return; }
        if (count == null) { SecurityErrors.write(request, response, 503, "Login is temporarily unavailable"); return; }
        if (count > limit) {
            response.setHeader("Retry-After", String.valueOf(window));
            SecurityErrors.write(request, response, 429, "Too many login attempts"); return;
        }
        chain.doFilter(request, response);
    }
}
