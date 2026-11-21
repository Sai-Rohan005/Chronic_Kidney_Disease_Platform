package com.ckd.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityAutoConfiguration {
    @Bean @Lazy(false)
    public JwtTokenService jwtTokenService(@Value("${app.jwt.secret}") String secret, StringRedisTemplate redis) {
        return new JwtTokenService(secret, redis);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenService tokens, StringRedisTemplate redis,
            @Value("${ckd.security.auth-service:false}") boolean authService,
            @Value("${ckd.security.login-limit:5}") int loginLimit,
            @Value("${ckd.security.login-window-seconds:60}") int loginWindow) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> SecurityErrors.write(req, res, 401, "Authentication required"))
                        .accessDeniedHandler((req, res, ex) -> SecurityErrors.write(req, res, 403, "Access denied")))
                .authorizeHttpRequests(rules -> {
                    if (authService) rules.requestMatchers(HttpMethod.POST, "/auth/login", "/auth/register").permitAll();
                    rules.requestMatchers("/predictions", "/predictions/**").hasRole("Doctor").anyRequest().authenticated();
                })
                .addFilterBefore(new JwtBearerFilter(tokens, authService), UsernamePasswordAuthenticationFilter.class);
        if (authService) http.addFilterBefore(new LoginRateLimitFilter(redis, loginLimit, loginWindow), JwtBearerFilter.class);
        return http.build();
    }
}
