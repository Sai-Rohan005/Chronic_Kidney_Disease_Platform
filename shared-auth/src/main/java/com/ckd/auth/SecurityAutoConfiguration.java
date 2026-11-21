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
    @org.springframework.core.annotation.Order(1)
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
                .addFilterBefore(new RequestCorrelationFilter(), org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .addFilterBefore(new JwtBearerFilter(tokens, authService), UsernamePasswordAuthenticationFilter.class);
        if (authService) http.addFilterBefore(new LoginRateLimitFilter(redis, loginLimit, loginWindow), JwtBearerFilter.class);
        return http.build();
    }

    @Bean
    @org.springframework.core.annotation.Order(0)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "management.server.port")
    public SecurityFilterChain managementSecurityFilterChain(HttpSecurity http, org.springframework.core.env.Environment environment) throws Exception {
        http.securityMatcher(request -> {
                    int configured = environment.getProperty("management.server.port", Integer.class, -1);
                    int actual = configured == 0 ? environment.getProperty("local.management.port", Integer.class, -1) : configured;
                    int applicationPort = environment.getProperty("local.server.port", Integer.class,
                            environment.getProperty("server.port", Integer.class, 8080));
                    return actual > 0 && actual != applicationPort && request.getLocalPort() == actual;
                })
                .csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> SecurityErrors.write(req, res, 403, "Management endpoint is not exposed"))
                        .accessDeniedHandler((req, res, ex) -> SecurityErrors.write(req, res, 403, "Management endpoint is not exposed")))
                .authorizeHttpRequests(rules -> rules.requestMatchers(HttpMethod.GET,
                        "/actuator/health", "/actuator/health/**", "/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus")
                        .permitAll().anyRequest().denyAll())
                .addFilterBefore(new RequestCorrelationFilter(), org.springframework.security.web.context.SecurityContextHolderFilter.class);
        return http.build();
    }
}
