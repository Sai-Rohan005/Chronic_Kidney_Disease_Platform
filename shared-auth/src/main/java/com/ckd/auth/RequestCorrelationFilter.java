package com.ckd.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.slf4j.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Logs bounded operational fields only: no URL, query, body, user, headers, or exception text. */
public final class RequestCorrelationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var previous = MDC.getCopyOfContextMap();
        MDC.put(RequestCorrelation.MDC_KEY, RequestCorrelation.normalize(request.getHeader(RequestCorrelation.HEADER)));
        response.setHeader(RequestCorrelation.HEADER, MDC.get(RequestCorrelation.MDC_KEY));
        boolean completed = false;
        try { chain.doFilter(request, response); completed = true; }
        finally {
            // Unhandled exceptions may precede the container's eventual 500 response.
            MDC.put("status", String.valueOf(completed ? response.getStatus() : 500));
            MDC.put("method", switch (request.getMethod()) {
                case "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS" -> request.getMethod();
                default -> "OTHER";
            });
            log.info("request_completed");
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }
}
