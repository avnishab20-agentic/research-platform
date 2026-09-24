package com.comeback.researchplatform.controlplane.auth;

import com.comeback.researchplatform.controlplane.config.JwtProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private JdbcTemplate jdbc;
    private BCryptPasswordEncoder encoder;
    private JwtUtil jwtUtil;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        encoder = new BCryptPasswordEncoder(4); // lowest cost: same behaviour, fast tests
        String secret = Base64.getEncoder().encodeToString(new byte[32]);
        jwtUtil = new JwtUtil(new JwtProperties(secret, Duration.ofHours(24), Duration.ofDays(7), "http://localhost/"));
        controller = new AuthController(jdbc, encoder, jwtUtil);
    }

    private Claims claimsOf(AuthController.TokenResponse response) {
        return jwtUtil.parse(response.token());
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(status));
    }

    private void storedUser(String email, String hash) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", USER_ID);
        row.put("password_hash", hash);
        when(jdbc.queryForList(startsWith("SELECT id, password_hash"), eq(email))).thenReturn(List.of(row));
    }

    @Test
    void registerStoresALowercasedEmailAndAHashAndSignsTheUserIn() {
        when(jdbc.queryForObject(startsWith("INSERT INTO users"), eq(UUID.class), eq("new@example.com"), anyString()))
                .thenReturn(USER_ID);

        var response = controller.register(new AuthController.Credentials("  New@Example.COM ", "correct-horse"));

        assertThat(claimsOf(response).getSubject()).isEqualTo(USER_ID.toString());
        assertThat(claimsOf(response).get("role", String.class)).isEqualTo("USER");
    }

    @Test
    void registerNeverStoresThePlainPassword() {
        when(jdbc.queryForObject(startsWith("INSERT INTO users"), eq(UUID.class), eq("a@b.com"), anyString()))
                .thenReturn(USER_ID);

        controller.register(new AuthController.Credentials("a@b.com", "correct-horse"));

        verify(jdbc, never()).queryForObject(anyString(), eq(UUID.class), eq("a@b.com"), eq("correct-horse"));
    }

    @Test
    void registeringATakenEmailIsA409() {
        when(jdbc.queryForObject(startsWith("INSERT INTO users"), eq(UUID.class), eq("a@b.com"), anyString()))
                .thenThrow(new DuplicateKeyException("users_email_key"));

        assertStatus(() -> controller.register(new AuthController.Credentials("a@b.com", "correct-horse")),
                HttpStatus.CONFLICT);
    }

    @Test
    void passwordsOutside8To72BytesAreA400() {
        assertStatus(() -> controller.register(new AuthController.Credentials("a@b.com", "short")), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.register(new AuthController.Credentials("a@b.com", "x".repeat(73))), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.register(new AuthController.Credentials("a@b.com", null)), HttpStatus.BAD_REQUEST);
    }

    @Test
    void aBlankEmailIsA400() {
        assertStatus(() -> controller.register(new AuthController.Credentials("  ", "correct-horse")), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.register(new AuthController.Credentials(null, "correct-horse")), HttpStatus.BAD_REQUEST);
    }

    @Test
    void loginWithTheRightPasswordRecordsTheLoginAndSignsIn() {
        storedUser("a@b.com", encoder.encode("correct-horse"));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("User-Agent", "test-agent");

        var response = controller.login(new AuthController.Credentials("A@B.com", "correct-horse"), request);

        assertThat(claimsOf(response).getSubject()).isEqualTo(USER_ID.toString());
        verify(jdbc).update(startsWith("UPDATE users SET last_login_at"), eq(USER_ID));
        verify(jdbc).update(startsWith("INSERT INTO login_events"), eq(USER_ID), eq("203.0.113.7"), eq("test-agent"));
    }

    @Test
    void aWrongPasswordAndAnUnknownEmailGetTheSame401() {
        storedUser("a@b.com", encoder.encode("correct-horse"));
        var request = new MockHttpServletRequest();

        assertStatus(() -> controller.login(new AuthController.Credentials("a@b.com", "wrong-horse"), request),
                HttpStatus.UNAUTHORIZED);
        assertStatus(() -> controller.login(new AuthController.Credentials("ghost@b.com", "correct-horse"), request),
                HttpStatus.UNAUTHORIZED);
        verify(jdbc, never()).update(startsWith("INSERT INTO login_events"), eq(USER_ID), anyString(), anyString());
    }

    @Test
    void aGoogleOnlyAccountHasNoPasswordToMatch() {
        storedUser("a@b.com", null);

        assertStatus(() -> controller.login(new AuthController.Credentials("a@b.com", "anything-at-all"),
                new MockHttpServletRequest()), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aGuestGetsAGuestTokenWithoutADatabaseRow() {
        var claims = claimsOf(controller.guest());

        assertThat(claims.getSubject()).startsWith("guest:");
        assertThat(claims.get("role", String.class)).isEqualTo("GUEST");
        verify(jdbc, never()).update(anyString());
    }

    @Test
    void refreshSwapsAValidTokenForANewOneAndRejectsABadOne() {
        String token = jwtUtil.generate("user-1", "USER");

        var refreshed = controller.refresh("Bearer " + token);

        assertThat(claimsOf(refreshed).getSubject()).isEqualTo("user-1");
        assertStatus(() -> controller.refresh("Bearer not.a.token"), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> controller.refresh("Bearer "), HttpStatus.UNAUTHORIZED);
    }
}
