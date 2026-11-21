package com.ckd.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.data.redis.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtValidationTest {
    private byte[] key;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private JwtTokenService tokens;
    @BeforeEach @SuppressWarnings("unchecked") void setUp() {
        key = new byte[32]; new SecureRandom().nextBytes(key);
        redis = mock(StringRedisTemplate.class); values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        tokens = new JwtTokenService(Base64.getEncoder().encodeToString(key), redis);
    }
    private JwtBuilder claims() {
        return Jwts.builder().subject("patient@example.invalid").claim("role", "Patient").id("test-jti")
                .issuedAt(Date.from(Instant.now().minusSeconds(120))).expiration(Date.from(Instant.now().plusSeconds(120)));
    }
    private String signed(JwtBuilder builder) { return builder.signWith(Keys.hmacShaKeyFor(key)).compact(); }
    @Test void issuedTokenHasExpectedClaimsUniqueIdAndOneHourLifetime() {
        var first = tokens.issue("patient@example.invalid", "Patient");
        var second = tokens.issue("patient@example.invalid", "Patient");
        var payload = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(key)).build().parseSignedClaims(first.value()).getPayload();
        assertEquals("patient@example.invalid", payload.getSubject()); assertEquals("Patient", payload.get("role"));
        assertFalse(payload.getId().isBlank()); assertNotEquals(first.value(), second.value());
        assertEquals(3600, (payload.getExpiration().getTime() - payload.getIssuedAt().getTime()) / 1000);
        verify(values).set(eq("auth:token:" + payload.getId()), eq(first.value()), eq(java.time.Duration.ofMinutes(60)));
    }
    @Test void expiredTokenIsRejectedBeforeRedisLookup() {
        String token = signed(claims().expiration(Date.from(Instant.now().minusSeconds(60))));
        assertTrue(tokens.authenticate(token).isEmpty()); verifyNoInteractions(values);
    }
    @Test void missingJtiIsRejectedBeforeRedisLookup() {
        String token = signed(claims().id(null));
        assertTrue(tokens.authenticate(token).isEmpty()); verifyNoInteractions(values);
    }
    @Test void missingExpiryIsRejectedBeforeRedisLookup() {
        assertTrue(tokens.authenticate(signed(claims().expiration(null))).isEmpty()); verifyNoInteractions(values);
    }
    @Test void blankJtiIsRejectedBeforeRedisLookup() {
        assertTrue(tokens.authenticate(signed(claims().id(" "))).isEmpty()); verifyNoInteractions(values);
    }
    @Test void missingSubjectIsRejectedBeforeRedisLookup() {
        assertTrue(tokens.authenticate(signed(claims().subject(null))).isEmpty()); verifyNoInteractions(values);
    }
    @Test void unsupportedRoleIsRejectedBeforeRedisLookup() {
        assertTrue(tokens.authenticate(signed(claims().claim("role", "Admin"))).isEmpty()); verifyNoInteractions(values);
    }
    @Test void missingRoleIsRejectedBeforeRedisLookup() {
        assertTrue(tokens.authenticate(signed(claims().claim("role", null))).isEmpty()); verifyNoInteractions(values);
    }
    @Test void payloadTamperingCannotGrantDoctorRole() {
        String[] parts = signed(claims()).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).replace("Patient", "Doctor");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertTrue(tokens.authenticate(tampered).isEmpty()); verifyNoInteractions(values);
    }
    @Test void signatureFromAnotherKeyIsRejected() {
        byte[] other = new byte[32]; new SecureRandom().nextBytes(other);
        String token = claims().signWith(Keys.hmacShaKeyFor(other)).compact();
        assertTrue(tokens.authenticate(token).isEmpty()); verifyNoInteractions(values);
    }
    @Test void matchingRedisEntryAuthenticatesAndNormalizesSubject() {
        String token = signed(claims().subject(" Patient@Example.Invalid "));
        when(values.get("auth:token:test-jti")).thenReturn(token);
        assertEquals(new JwtTokenService.AuthenticatedUser("patient@example.invalid", "Patient"), tokens.authenticate(token).orElseThrow());
    }
    @Test void missingAllowlistEntryIsRevoked() { assertTrue(tokens.authenticate(signed(claims())).isEmpty()); }
    @Test void allowlistEntryMustMatchTheExactToken() {
        when(values.get("auth:token:test-jti")).thenReturn("another-test-token");
        assertTrue(tokens.authenticate(signed(claims())).isEmpty());
    }
    @Test void redisOutageFailsClosed() {
        when(values.get(anyString())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture outage"));
        assertTrue(tokens.authenticate(signed(claims())).isEmpty());
    }
    @Test void revocationDeletesOnlyTheSignedJtiEntry() {
        when(redis.delete("auth:token:test-jti")).thenReturn(true);
        assertTrue(tokens.revoke(signed(claims()))); verify(redis).delete("auth:token:test-jti");
    }
    @Test void expiredTokenCannotDeleteAnAllowlistEntry() {
        assertFalse(tokens.revoke(signed(claims().expiration(Date.from(Instant.now().minusSeconds(60))))));
        verify(redis, never()).delete(anyString());
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"broken.jwt", "not-a-token"})
    void malformedTokensCannotAuthenticateOrRevoke(String token) {
        assertTrue(tokens.authenticate(token).isEmpty()); assertFalse(tokens.revoke(token));
        verifyNoInteractions(values); verify(redis, never()).delete(anyString());
    }
}
