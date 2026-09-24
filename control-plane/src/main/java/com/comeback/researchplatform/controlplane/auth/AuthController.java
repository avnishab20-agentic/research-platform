package com.comeback.researchplatform.controlplane.auth;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record Credentials(String email, String password) {}

    // Just the token: the role and expiry are inside it, readable by anyone (signed, not encrypted).
    public record TokenResponse(String token) {}

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/register")
    public TokenResponse register(@RequestBody Credentials body) {
        String password = body.password();
        // BCrypt reads only the first 72 bytes and Spring throws past that, so say so up front.
        if (password == null || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password must be 8 to 72 characters");
        }
        try {
            // No "does this email exist?" check first: two sign-ups could both pass it.
            // The UNIQUE constraint can't be raced.
            UUID id = jdbc.queryForObject(
                    "INSERT INTO users (email, password_hash) VALUES (?, ?) RETURNING id",
                    UUID.class, normalize(body.email()), passwordEncoder.encode(password));
            return new TokenResponse(jwtUtil.generate(id.toString(), "USER"));
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
    }
    // Transactional so last_login_at and the login_events row are written together or not at all.
    @Transactional
    @PostMapping("/login")
    public TokenResponse login(@RequestBody Credentials body, HttpServletRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, password_hash FROM users WHERE email = ?", normalize(body.email()));
        String hash = rows.isEmpty() ? null : (String) rows.get(0).get("password_hash");
        // Unknown email, a Google-only account (no hash) and a wrong password all get the
        // same answer, so the response never reveals which emails have accounts.
        if (hash == null || body.password() == null || !passwordEncoder.matches(body.password(), hash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid email or password");
        }
        UUID id = (UUID) rows.get(0).get("id");
        jdbc.update("UPDATE users SET last_login_at = now() WHERE id = ?", id);
        jdbc.update("INSERT INTO login_events (user_id, method, ip, user_agent) VALUES (?, 'PASSWORD', ?, ?)",
                id, request.getRemoteAddr(), request.getHeader("User-Agent"));
        return new TokenResponse(jwtUtil.generate(id.toString(), "USER"));
    }

// No database row: a guest is only a token. Clearing browser storage mints a new guest,
// which resets the 3-question cap -- accepted for a demo.
@PostMapping("/guest")
public TokenResponse guest() {
    return new TokenResponse(jwtUtil.generate("guest:" + UUID.randomUUID(), "GUEST"));
}

    @PostMapping("/refresh")
    public TokenResponse refresh(@RequestHeader("Authorization") String header) {
        try {
            return new TokenResponse(jwtUtil.refresh(header.replaceFirst("^Bearer ", "")));
        } catch (JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "token invalid or expired");
        }
    }

    // Stored lowercased: A@Gmail.com and a@gmail.com are one inbox, but UNIQUE compares exact strings.
    private static String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required");
        }
        return email.trim().toLowerCase();
    }
}
