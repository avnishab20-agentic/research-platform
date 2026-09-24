package com.comeback.researchplatform.controlplane.auth;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtUtil jwtUtil,
                                           OAuth2LoginSuccessHandler oauth2SuccessHandler) throws Exception {
        return http
                // CSRF attacks ride on cookies; the token travels in a header, so there's nothing to forge.
                // The only session is the OAuth2 redirect's, protected by its own state parameter
                // and invalidated by OAuth2LoginSuccessHandler as soon as the token is issued.
                .csrf(AbstractHttpConfigurer::disable) // NOSONAR java:S4502 -- bearer-token API, see above
                // Lets RunController's @CrossOrigin answer the browser's preflight instead of a 401.
                .cors(Customizer.withDefaults())
                // No STATELESS here: the Google/GitHub redirect dance keeps its state in a short-lived
                // session. OAuth2LoginSuccessHandler throws that session away once the token is issued.
                // Without a request cache, a plain 401 on the API never creates a session either.
                .requestCache(RequestCacheConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // SSE finishes on an ASYNC dispatch and errors render on an ERROR one; the
                        // original request was already checked, so don't turn these into 401s.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/auth/**", "/actuator/health", "/oauth2/**", "/login/**").permitAll()
                        .anyRequest().authenticated())
                // A plain 401, not Spring's default redirect to a login form.
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // /oauth2/authorization/google (or /github) starts a login; the provider sends the
                // user back to /login/oauth2/code/{provider}, and the success handler takes over.
                .oauth2Login(o -> o.successHandler(oauth2SuccessHandler))
                .addFilterBefore(new JwtAuthFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}