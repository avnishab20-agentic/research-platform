package com.comeback.researchplatform.retrievalservice.config;



import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(int capacity, double refillRate, int maxWaitAttempts ) {
}
