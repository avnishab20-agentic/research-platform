package com.comeback.researchplatform.controlplane.auth;

import com.comeback.researchplatform.controlplane.config.JwtProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private static JwtUtil utilWith(byte[] key, Duration userTtl) {
        String secret = Base64.getEncoder().encodeToString(key);
        return new JwtUtil(new JwtProperties(secret, userTtl, Duration.ofDays(7), "http://localhost/"));
    }

    private static JwtUtil util() {
        return utilWith(new byte[32], Duration.ofHours(24));
    }

    @Test
    void aTokenItCreatesItAlsoAccepts() {
        var claims = util().parse(util().generate("user-1", "USER"));

        assertThat(claims.getSubject()).isEqualTo("user-1");
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRejected() {
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        String forged = utilWith(otherKey, Duration.ofHours(24)).generate("admin", "USER");

        assertThatThrownBy(() -> util().parse(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void anExpiredTokenIsRejected() {
        String expired = utilWith(new byte[32], Duration.ofSeconds(-1)).generate("user-1", "USER");

        assertThatThrownBy(() -> util().parse(expired)).isInstanceOf(JwtException.class);
    }

    @Test
    void guestsGetTheLongerLifetime() {
        var claims = util().parse(util().generate("guest:x", "GUEST"));
        long lifetime = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();

        assertThat(lifetime).isEqualTo(Duration.ofDays(7).toMillis());
    }

    @Test
    void refreshKeepsWhoYouAreAndTheRole() {
        var claims = util().parse(util().refresh(util().generate("guest:x", "GUEST")));

        assertThat(claims.getSubject()).isEqualTo("guest:x");
        assertThat(claims.get("role", String.class)).isEqualTo("GUEST");
    }

    @Test
    void anEmptyTokenIsAJwtExceptionNotACrash() {
        assertThatThrownBy(() -> util().parse("")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> util().parse(null)).isInstanceOf(JwtException.class);
    }

    @Test
    void aSecretShorterThan32BytesStopsStartup() {
        assertThatThrownBy(() -> utilWith(new byte[16], Duration.ofHours(1)))
                .isInstanceOf(WeakKeyException.class);
    }
}
