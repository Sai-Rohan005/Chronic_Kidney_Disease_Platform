package com.ckd.auth;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.env.MockPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SecretStartupTest {
    @Test void missingOrShortSecretPreventsSecurityContextStartup() {
        for (String secret : new String[] {null, java.util.Base64.getEncoder().encodeToString(new byte[31])}) {
            try (var context = new AnnotationConfigWebApplicationContext()) {
                context.setServletContext(new MockServletContext());
                var properties = new MockPropertySource();
                if (secret != null) properties.withProperty("app.jwt.secret", secret);
                context.getEnvironment().getPropertySources().addFirst(properties);
                context.register(Config.class);
                assertThrows(org.springframework.beans.BeansException.class, context::refresh);
            }
        }
    }
    @Configuration @Import(SecurityAutoConfiguration.class)
    static class Config {
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
    }
}
