package com.comeback.researchplatform.controlplane.auth;

import com.comeback.researchplatform.controlplane.config.JwtProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs after Google or GitHub confirms who the user is. From here on they're treated
 * exactly like a password user: find or create their users row, then hand them OUR token.
 */
@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final JwtProperties props;

    public OAuth2LoginSuccessHandler(JdbcTemplate jdbc, JwtUtil jwtUtil, JwtProperties props) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.props = props;
    }

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        var oauth = (OAuth2AuthenticationToken) authentication;
        String provider = oauth.getAuthorizedClientRegistrationId();
        Map<String, Object> attrs = oauth.getPrincipal().getAttributes();

        UUID userId = switch (provider) {
            // Only a verified email may link to an existing account; otherwise anyone could
            // add an unverified address at Google and take over the password account behind it.
            case "google" -> findOrCreate("google_id", (String) attrs.get("sub"),
                    Boolean.TRUE.equals(attrs.get("email_verified")) ? (String) attrs.get("email") : null);
            // GitHub only shows an email if the user made it public, and a public one is verified.
            case "github" -> findOrCreate("github_id", String.valueOf(attrs.get("id")), (String) attrs.get("email"));
            default -> throw new IllegalStateException("unknown login provider: " + provider);
        };

        jdbc.update("UPDATE users SET last_login_at = now() WHERE id = ?", userId);
        jdbc.update("INSERT INTO login_events (user_id, method, ip, user_agent) VALUES (?, ?, ?, ?)",
                userId, provider.toUpperCase(), request.getRemoteAddr(), request.getHeader("User-Agent"));

        // The redirect dance to the provider needed a session; the token replaces it now.
        // Dropping it means no cookie can authenticate later calls, which keeps CSRF off the table.
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        // In the fragment (#), not the query (?): browsers never send the fragment to a
        // server, so the token stays out of access logs and proxy logs.
        response.sendRedirect(props.loginRedirect() + "#token=" + jwtUtil.generate(userId.toString(), "USER"));
    }

    // idColumn is always one of the two literals above, never user input, so concatenating it is safe.
    private UUID findOrCreate(String idColumn, String providerId, String email) {
        // 1. Logged in with this provider before.
        List<UUID> found = jdbc.queryForList(
                "SELECT id FROM users WHERE " + idColumn + " = ?", UUID.class, providerId);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        if (email == null) {
            // Nothing trustworthy to link or store. .invalid is a reserved TLD: it can never
            // be a real inbox, so it can never collide with a real account's email.
            return insert(idColumn, providerId, idColumn + "-" + providerId + "@no-email.invalid");
        }
        String normalized = email.trim().toLowerCase();
        // 2. Same email already has an account (password or the other provider): attach to it.
        List<UUID> linked = jdbc.queryForList(
                "UPDATE users SET " + idColumn + " = ? WHERE email = ? AND " + idColumn + " IS NULL RETURNING id",
                UUID.class, providerId, normalized);
        if (!linked.isEmpty()) {
            return linked.get(0);
        }
        // 3. Brand new person.
        return insert(idColumn, providerId, normalized);
    }

    private UUID insert(String idColumn, String providerId, String email) {
        return jdbc.queryForObject(
                "INSERT INTO users (email, " + idColumn + ") VALUES (?, ?) RETURNING id",
                UUID.class, email, providerId);
    }
}
