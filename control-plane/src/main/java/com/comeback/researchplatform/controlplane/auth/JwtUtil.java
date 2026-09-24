package com.comeback.researchplatform.controlplane.auth;

import com.comeback.researchplatform.controlplane.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtUtil {

    private final JwtProperties props;
    private final SecretKey key;

    public JwtUtil(JwtProperties props) {
        this.props = props;
        // The secret is base64 text; the key is the bytes it encodes. hmacShaKeyFor throws
        // WeakKeyException under 32 bytes, so a short secret stops startup here.
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(props.secret()));
    }

    public String generate(String subject, String role) {
        Duration ttl = "GUEST".equals(role) ? props.guestTtl() : props.userTtl();
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** Throws JwtException if the signature is wrong or the token has expired. */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    // ponytail: a still-valid token buys a fresh one, so a stolen token can be refreshed
    // forever. Real refresh tokens (stored in Postgres, revocable) fix that when it matters.
    public String refresh(String token) throws JwtException {
        Claims claims = parse(token);
        return generate(claims.getSubject(), claims.get("role", String.class));
    }
}

