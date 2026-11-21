package com.ckd.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.*;
import java.util.*;

/** The existing auth:token:<jti> allowlist is deleted on logout and expires after 60 minutes. */
public class JwtTokenService {
    private static final Duration TOKEN_TTL = Duration.ofMinutes(60);
    private final SecretKey signingKey;
    private final StringRedisTemplate redis;

    public JwtTokenService(String base64Secret, StringRedisTemplate redis) {
        try {
            if (base64Secret == null || base64Secret.isBlank()) throw new IllegalArgumentException();
            byte[] bytes = Base64.getDecoder().decode(base64Secret);
            if (bytes.length < 32 || !Base64.getEncoder().encodeToString(bytes).equals(base64Secret)) throw new IllegalArgumentException();
            signingKey = Keys.hmacShaKeyFor(bytes);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("JWT_SECRET is required and must be base64 encoding at least 32 random bytes");
        }
        this.redis = redis;
    }

    public IssuedToken issue(String email, String role) {
        if (!Set.of("Patient", "Doctor").contains(role)) throw new IllegalArgumentException("Unsupported role");
        Instant now = Instant.now();
        Instant expiresAt = now.plus(TOKEN_TTL);
        String id = UUID.randomUUID().toString();
        String token = Jwts.builder().subject(email).claim("role", role).id(id)
                .issuedAt(Date.from(now)).expiration(Date.from(expiresAt)).signWith(signingKey).compact();
        redis.opsForValue().set("auth:token:" + id, token, TOKEN_TTL);
        return new IssuedToken(token, expiresAt);
    }

    public Optional<AuthenticatedUser> authenticate(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
            String id = claims.getId(), email = claims.getSubject(), role = claims.get("role", String.class);
            if (id == null || id.isBlank() || email == null || email.isBlank() || claims.getExpiration() == null
                    || role == null || !Set.of("Patient", "Doctor").contains(role)) return Optional.empty();
            String stored = redis.opsForValue().get("auth:token:" + id);
            if (stored == null || !MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedUser(email.trim().toLowerCase(Locale.ROOT), role));
        } catch (JwtException | IllegalArgumentException | DataAccessException exception) {
            return Optional.empty(); // Redis failure must never bypass revocation checks.
        }
    }

    public boolean revoke(String token) {
        try {
            String id = Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload().getId();
            return id != null && Boolean.TRUE.equals(redis.delete("auth:token:" + id));
        } catch (JwtException | IllegalArgumentException exception) {
            return false;
        }
    }

    public static String bearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
    public record IssuedToken(String value, Instant expiresAt) {}
    public record AuthenticatedUser(String email, String role) implements Principal {
        @Override public String getName() { return email; }
    }
}
