package com.ckd.auth;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Service-layer authorization; identity is always taken from the validated SecurityContext. */
public final class CurrentCaller {
    private CurrentCaller() {}
    public static JwtTokenService.AuthenticatedUser user() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String role = auth.getAuthorities().stream().map(a -> a.getAuthority())
                .filter(a -> a.equals("ROLE_Patient") || a.equals("ROLE_Doctor"))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Unsupported role"));
        return new JwtTokenService.AuthenticatedUser(auth.getName(), role.substring(5));
    }
    public static void requireRole(String role) {
        if (!user().role().equals(role)) forbidden();
    }
    public static void requireSelf(String email) {
        if (email == null || !user().email().equalsIgnoreCase(email.trim())) forbidden();
    }
    public static void requirePatient(String email) {
        requireRole("Patient");
        requireSelf(email);
    }
    public static void requireAppointment(Map<String, ?> appointment) {
        var user = user();
        Object owner = appointment.get(user.role().equals("Patient") ? "patient_email" : "doctor_email");
        if (!(owner instanceof String email) || !user.email().equalsIgnoreCase(email.trim())) forbidden();
    }
    public static String authorizationHeader() {
        user();
        Object token = SecurityContextHolder.getContext().getAuthentication().getCredentials();
        if (!(token instanceof String value) || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Caller token is unavailable");
        }
        return "Bearer " + value;
    }
    private static void forbidden() { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied"); }
}
