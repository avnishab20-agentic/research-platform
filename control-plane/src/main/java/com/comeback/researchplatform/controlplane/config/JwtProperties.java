package com.comeback.researchplatform.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * Signing key and token lifetimes. The secret has no fallback, so a missing JWT_SECRET
 * stops startup. loginRedirect is where a Google/GitHub login sends the browser back to.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, Duration userTtl, Duration guestTtl, String loginRedirect) {}
