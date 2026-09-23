package com.comeback.researchplatform.retrievalservice.config;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class SearxngClientConfig {

    @Bean
    public RestClient searxngRestClient(@Value(("${searxng.base-url}"))String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    // Tavily's /search returns results[] with url/title/content -- the same shape
    // as SearXNG's JSON, so SearxngSearchResponse maps it unchanged.
    @Bean
    public RestClient tavilyRestClient(@Value("${tavily.base-url:https://api.tavily.com}") String baseUrl,
                                       @Value("${tavily.api-key:}") String apiKey) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }
}
