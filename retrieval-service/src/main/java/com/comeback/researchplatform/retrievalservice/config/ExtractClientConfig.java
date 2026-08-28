package com.comeback.researchplatform.retrievalservice.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ExtractClientConfig {

    @Bean
    public RestClient extractorRestClient(ExtractProperties props) {
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .build();
    }
}
