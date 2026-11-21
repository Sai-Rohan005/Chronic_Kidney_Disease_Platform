package com.ckd.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.Filter;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockServletContext;
import org.springframework.mock.env.MockPropertySource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

class BearerSecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private final byte[] key = new byte[32];
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private final AtomicLong attempts = new AtomicLong();

    @BeforeEach @SuppressWarnings("unchecked") void setUp() {
        new SecureRandom().nextBytes(key);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MockPropertySource()
                .withProperty("app.jwt.secret", Base64.getEncoder().encodeToString(key))
                .withProperty("ckd.security.auth-service", "true")
                .withProperty("ckd.security.login-limit", "2"));
        context.register(Config.class);
        context.refresh();
        redis = context.getBean(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> tokens.get(call.getArgument(0)));
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenAnswer(call -> attempts.incrementAndGet());
        mvc = webAppContextSetup(context).addFilters(context.getBeansOfType(Filter.class).values().toArray(Filter[]::new)).build();
    }
    @AfterEach void close() { SecurityContextHolder.clearContext(); context.close(); }

    private String token(String role, Instant expiration, boolean active) {
        String jti = UUID.randomUUID().toString();
        String result = Jwts.builder().subject("patient@example.invalid").claim("role", role).id(jti)
                .issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(expiration)).signWith(Keys.hmacShaKeyFor(key)).compact();
        if (active) tokens.put("auth:token:" + jti, result);
        return result;
    }
    @Test void noTokenIs401() throws Exception {
        mvc.perform(get("/private")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401)).andExpect(jsonPath("$.path").value("/private"));
    }
    @Test void expiredTokenIs401() throws Exception {
        mvc.perform(get("/private").header("Authorization", "Bearer " + token("Patient", Instant.now().minusSeconds(60), true)))
                .andExpect(status().isUnauthorized());
    }
    @Test void revokedTokenIs401() throws Exception {
        mvc.perform(get("/private").header("Authorization", "Bearer " + token("Patient", Instant.now().plusSeconds(60), false)))
                .andExpect(status().isUnauthorized());
    }
    @Test void badSignatureIs401() throws Exception {
        byte[] other = new byte[32]; new SecureRandom().nextBytes(other);
        String token = Jwts.builder().subject("p@example.invalid").claim("role", "Patient").id("forged")
                .expiration(Date.from(Instant.now().plusSeconds(60))).signWith(Keys.hmacShaKeyFor(other)).compact();
        mvc.perform(get("/private").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
    @Test void roleIsInSecurityContext() throws Exception {
        mvc.perform(get("/private").header("Authorization", "Bearer " + token("Doctor", Instant.now().plusSeconds(60), true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("patient@example.invalid"))
                .andExpect(jsonPath("$.role").value("ROLE_Doctor"));
        mvc.perform(get("/private")).andExpect(status().isUnauthorized());
    }
    @Test void patientCannotPredict() throws Exception {
        mvc.perform(post("/predictions").header("Authorization", "Bearer " + token("Patient", Instant.now().plusSeconds(60), true)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
    }
    @Test void doctorCanPredict() throws Exception {
        mvc.perform(post("/predictions").header("Authorization", "Bearer " + token("Doctor", Instant.now().plusSeconds(60), true)))
                .andExpect(status().isOk());
    }
    @Test void publicRoutesAreOnlyLoginAndRegisterPosts() throws Exception {
        mvc.perform(post("/auth/login")).andExpect(status().isOk());
        mvc.perform(post("/auth/register")).andExpect(status().isOk());
        for (String path : List.of("/auth/getRole", "/auth/getUser", "/auth/getAllDoctors", "/error", "/unknown", "/auth/login/extra")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/auth/login")).andExpect(status().isUnauthorized());
    }
    @Test void loginRateLimitIs429EvenWithForwardedHeaderSpoofing() throws Exception {
        mvc.perform(post("/auth/login")).andExpect(status().isOk());
        mvc.perform(post("/auth/login")).andExpect(status().isOk());
        mvc.perform(post("/auth/login").header("X-Forwarded-For", "different-client"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
    @Test void redisFailureDoesNotAuthenticate() throws Exception {
        when(values.get(anyString())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
        mvc.perform(get("/private").header("Authorization", "Bearer " + token("Patient", Instant.now().plusSeconds(60), true)))
                .andExpect(status().isUnauthorized());
    }
    @Test void missingExpiryIs401() throws Exception {
        String token = Jwts.builder().subject("p@example.invalid").claim("role", "Patient").id("no-exp")
                .signWith(Keys.hmacShaKeyFor(key)).compact();
        tokens.put("auth:token:no-exp", token);
        mvc.perform(get("/private").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
    @Configuration @EnableWebMvc @Import(SecurityAutoConfiguration.class)
    static class Config {
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean Probe probe() { return new Probe(); }
    }
    @RestController static class Probe {
        @GetMapping("/private") Map<String, String> privateRoute() {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            return authentication == null ? Map.of() : Map.of("email", authentication.getName(),
                    "role", authentication.getAuthorities().iterator().next().getAuthority());
        }
        @PostMapping({"/auth/login", "/auth/register", "/predictions"}) Map<String, String> postRoute() { return Map.of("ok", "true"); }
    }
}
