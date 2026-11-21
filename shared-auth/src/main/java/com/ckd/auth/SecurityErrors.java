package com.ckd.auth;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

final class SecurityErrors {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private SecurityErrors() {}
    static void write(HttpServletRequest request, HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        JSON.writeValue(response.getWriter(), Map.of("timestamp", Instant.now().toString(), "status", status,
                "error", HttpStatus.valueOf(status).getReasonPhrase(), "message", message, "path", request.getRequestURI()));
    }
}
