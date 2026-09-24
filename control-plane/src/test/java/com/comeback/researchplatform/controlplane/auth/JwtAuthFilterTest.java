package com.comeback.researchplatform.controlplane.auth;

import com.comeback.researchplatform.controlplane.config.JwtProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthFilterTest {

    private final JwtUtil jwtUtil = new JwtUtil(new JwtProperties(
            Base64.getEncoder().encodeToString(new byte[32]), Duration.ofHours(1), Duration.ofDays(7), "http://localhost/"));
    private final JwtAuthFilter filter = new JwtAuthFilter(jwtUtil);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Runs the filter and returns who Spring Security thinks is calling; asserts the request always continues. */
    private Authentication filter(MockHttpServletRequest request) throws Exception {
        var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).as("the request must always reach the next filter").isNotNull();
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void aValidBearerTokenIdentifiesTheCallerAndTheirRole() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/runs/x");
        request.addHeader("Authorization", "Bearer " + jwtUtil.generate("user-1", "USER"));

        Authentication auth = filter(request);

        assertThat(auth.getName()).isEqualTo("user-1");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
    }

    @Test
    void noTokenLeavesTheCallerAnonymous() throws Exception {
        assertThat(filter(new MockHttpServletRequest("GET", "/api/v1/runs/x"))).isNull();
    }

    @Test
    void aForgedTokenLeavesTheCallerAnonymousInsteadOfFailing() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/runs/x");
        request.addHeader("Authorization", "Bearer abc.def.ghi");

        assertThat(filter(request)).isNull();
    }

    @Test
    void theSseStreamAcceptsTheTokenAsAQueryParameter() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/runs/x/events");
        request.setParameter("token", jwtUtil.generate("guest:abc", "GUEST"));

        Authentication auth = filter(request);

        assertThat(auth.getName()).isEqualTo("guest:abc");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_GUEST");
    }

    @Test
    void otherEndpointsIgnoreATokenInTheUrl() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/runs/x");
        request.setParameter("token", jwtUtil.generate("user-1", "USER"));

        assertThat(filter(request)).isNull();
    }

    @Test
    void anEmptyTokenOnTheStreamIsAnonymousNotAnError() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/runs/x/events");
        request.setParameter("token", "");

        assertThat(filter(request)).isNull();
    }
}
