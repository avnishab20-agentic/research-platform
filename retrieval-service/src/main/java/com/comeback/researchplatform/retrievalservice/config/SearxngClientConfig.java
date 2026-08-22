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
}
