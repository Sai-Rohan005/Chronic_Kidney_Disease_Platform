package com.ckd.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtBearerFilter extends OncePerRequestFilter {
    private final JwtTokenService tokens;
    private final boolean authService;
    public JwtBearerFilter(JwtTokenService tokens, boolean authService) { this.tokens = tokens; this.authService = authService; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return authService && "POST".equals(request.getMethod())
                && (request.getRequestURI().equals(request.getContextPath() + "/auth/login") || request.getRequestURI().equals(request.getContextPath() + "/auth/register"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = JwtTokenService.bearerToken(request.getHeader("Authorization"));
        var user = token == null ? java.util.Optional.<JwtTokenService.AuthenticatedUser>empty() : tokens.authenticate(token);
        if (user.isEmpty()) {
            SecurityContextHolder.clearContext();
            SecurityErrors.write(request, response, 401, "Invalid, expired, or revoked token");
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user.get(), token,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.get().role()))));
        SecurityContextHolder.setContext(context);
        try { chain.doFilter(request, response); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
