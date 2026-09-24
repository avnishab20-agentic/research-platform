package com.comeback.researchplatform.controlplane.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
/** * Runs once per request: reads the token, and if it checks out, tells Spring Security
 * who is calling. A missing or bad token just leaves the caller anonymous;
 * SecurityConfig then decides whether that path needs a login.
 * Deliberately not a @Component: Boot would also register it as a plain servlet filter.
        */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = tokenFrom(request);
        if (token != null) {
            try {
                Claims claims = jwtUtil.parse(token);
                var role = new SimpleGrantedAuthority("ROLE_" + claims.get("role", String.class));
                var auth = new UsernamePasswordAuthenticationToken(claims.getSubject(), null, List.of(role));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException e) {
                // Forged or expired: stay anonymous, and a protected path answers 401.
            }
        }
        chain.doFilter(request, response);
    }
    private String tokenFrom(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length());
        }
        // The browser's EventSource can't set headers, so the SSE stream alone takes it in the URL.
        if (request.getRequestURI().endsWith("/events")) {
            return request.getParameter("token");
        }
        return null;
    }
}
