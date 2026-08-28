package com.comeback.researchplatform.retrievalservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;


@ConfigurationProperties(prefix = "extractor")
public record ExtractProperties (
    String baseUrl, int maxDocumentBytes,Duration cacheTtl, Duration connectTimeout,
    Duration readTimeout){

}



