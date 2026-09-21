package com.comeback.researchplatform.agentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "retrieval")
public record RetrievalProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {}
