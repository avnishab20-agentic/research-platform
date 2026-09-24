package com.comeback.researchplatform.controlplane.auth;

import com.comeback.researchplatform.controlplane.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

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

class OAuth2LoginSuccessHandlerTest {

    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String REDIRECT = "https://site.example/";

    private JdbcTemplate jdbc;
    private JwtUtil jwtUtil;
    private OAuth2LoginSuccessHandler handler;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        var props = new JwtProperties(Base64.getEncoder().encodeToString(new byte[32]),
                Duration.ofHours(24), Duration.ofDays(7), REDIRECT);
        jwtUtil = new JwtUtil(props);
        handler = new OAuth2LoginSuccessHandler(jdbc, jwtUtil, props);
        session = new MockHttpSession();
        request = new MockHttpServletRequest();
        request.setSession(session);
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("User-Agent", "test-agent");
        response = new MockHttpServletResponse();
    }

    private static OAuth2AuthenticationToken login(String provider, String nameKey, Map<String, Object> attrs) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(List.of(), attrs, nameKey), List.of(), provider);
    }

    private static Map<String, Object> google(String sub, String email, boolean verified) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", sub);
        attrs.put("email", email);
        attrs.put("email_verified", verified);
        return attrs;
    }

    private static Map<String, Object> github(int id, String email) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("id", id);
        attrs.put("login", "octo");
        attrs.put("email", email);
        return attrs;
    }

    /** The user id inside the token the browser was redirected with. */
    private String redirectedSubject() {
        String url = response.getRedirectedUrl();
        assertThat(url).startsWith(REDIRECT + "#token=");
        return jwtUtil.parse(url.substring((REDIRECT + "#token=").length())).getSubject();
    }

    @Test
    void aReturningGoogleUserIsFoundByTheirGoogleId() throws Exception {
        when(jdbc.queryForList(startsWith("SELECT id FROM users WHERE google_id"), eq(UUID.class), eq("g-1")))
                .thenReturn(List.of(USER_ID));

        handler.onAuthenticationSuccess(request, response, login("google", "sub", google("g-1", "a@b.com", true)));

        assertThat(redirectedSubject()).isEqualTo(USER_ID.toString());
        verify(jdbc).update(startsWith("UPDATE users SET last_login_at"), eq(USER_ID));
        verify(jdbc).update(startsWith("INSERT INTO login_events"), eq(USER_ID), eq("GOOGLE"),
                eq("203.0.113.9"), eq("test-agent"));
        verify(jdbc, never()).queryForObject(startsWith("INSERT INTO users"), eq(UUID.class), anyString(), anyString());
    }

    @Test
    void theOAuthSessionIsThrownAwayOnceTheTokenIsIssued() throws Exception {
        when(jdbc.queryForList(startsWith("SELECT id FROM users WHERE google_id"), eq(UUID.class), eq("g-1")))
                .thenReturn(List.of(USER_ID));

        handler.onAuthenticationSuccess(request, response, login("google", "sub", google("g-1", "a@b.com", true)));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aVerifiedGoogleEmailLinksToTheExistingPasswordAccount() throws Exception {
        when(jdbc.queryForList(startsWith("UPDATE users SET google_id"), eq(UUID.class), eq("g-1"), eq("a@b.com")))
                .thenReturn(List.of(USER_ID));

        handler.onAuthenticationSuccess(request, response, login("google", "sub", google("g-1", "A@B.com", true)));

        assertThat(redirectedSubject()).isEqualTo(USER_ID.toString());
        verify(jdbc, never()).queryForObject(startsWith("INSERT INTO users"), eq(UUID.class), anyString(), anyString());
    }

    @Test
    void aNewGoogleUserGetsANewRow() throws Exception {
        when(jdbc.queryForObject(startsWith("INSERT INTO users (email, google_id)"), eq(UUID.class),
                eq("new@b.com"), eq("g-2"))).thenReturn(USER_ID);

        handler.onAuthenticationSuccess(request, response, login("google", "sub", google("g-2", "new@b.com", true)));

        assertThat(redirectedSubject()).isEqualTo(USER_ID.toString());
    }

    @Test
    void anUnverifiedGoogleEmailIsNeverUsedToLinkAnAccount() throws Exception {
        when(jdbc.queryForObject(startsWith("INSERT INTO users (email, google_id)"), eq(UUID.class),
                eq("google-g-3@no-email.invalid"), eq("g-3"))).thenReturn(USER_ID);

        handler.onAuthenticationSuccess(request, response, login("google", "sub", google("g-3", "victim@b.com", false)));

        assertThat(redirectedSubject()).isEqualTo(USER_ID.toString());
        verify(jdbc, never()).queryForList(startsWith("UPDATE users SET google_id"), eq(UUID.class), anyString(), anyString());
    }

    @Test
    void aGithubUserWithAPrivateEmailGetsAPlaceholderAddress() throws Exception {
        when(jdbc.queryForObject(startsWith("INSERT INTO users (email, github_id)"), eq(UUID.class),
                eq("github-42@no-email.invalid"), eq("42"))).thenReturn(USER_ID);

        handler.onAuthenticationSuccess(request, response, login("github", "id", github(42, null)));

        assertThat(redirectedSubject()).isEqualTo(USER_ID.toString());
        verify(jdbc).update(startsWith("INSERT INTO login_events"), eq(USER_ID), eq("GITHUB"),
                eq("203.0.113.9"), eq("test-agent"));
    }

    @Test
    void aGithubUserWithAPublicEmailLinksByIt() throws Exception {
        when(jdbc.queryForList(startsWith("UPDATE users SET github_id"), eq(UUID.class), eq("42"), eq("octo@b.com")))
                .thenReturn(List.of(USER_ID));

        handler.onAuthenticationSuccess(request, response, login("github", "id", github(42, "octo@b.com")));

        assertThat(redirectedSubject()).isEqualTo(USER_ID.toString());
    }

    @Test
    void anUnknownProviderIsRefused() {
        var token = login("facebook", "id", new HashMap<>(Map.of("id", "1")));

        assertThatThrownBy(() -> handler.onAuthenticationSuccess(request, response, token))
                .isInstanceOf(IllegalStateException.class);
    }
}
