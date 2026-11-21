package com.ckd.auth;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class JwtTokenServiceTest {
    @Test void secretMustBePresentValidBase64AndAtLeast256Bits() {
        var redis = mock(StringRedisTemplate.class);
        for (String value : new String[] {null, "", "not base64!", Base64.getEncoder().encodeToString(new byte[31]), Base64.getEncoder().encodeToString(new byte[32]) + "!"}) {
            assertThrows(IllegalStateException.class, () -> new JwtTokenService(value, redis));
        }
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        assertDoesNotThrow(() -> new JwtTokenService(Base64.getEncoder().encodeToString(random), redis));
    }
    @Test void issuingAndRevokingUseTheSameRedisJtiKeyAndSixtyMinuteTtl() {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") org.springframework.data.redis.core.ValueOperations<String, String> values = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        var service = new JwtTokenService(Base64.getEncoder().encodeToString(random), redis);
        var token = service.issue("patient@example.invalid", "Patient");
        var key = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(key.capture(), eq(token.value()), eq(java.time.Duration.ofMinutes(60)));
        when(values.get(key.getValue())).thenReturn(token.value());
        assertTrue(service.authenticate(token.value()).isPresent());
        when(redis.delete(key.getValue())).thenReturn(true);
        assertTrue(service.revoke(token.value()));
        verify(redis).delete(key.getValue());
        when(values.get(key.getValue())).thenReturn(null);
        assertTrue(service.authenticate(token.value()).isEmpty());
    }
}
