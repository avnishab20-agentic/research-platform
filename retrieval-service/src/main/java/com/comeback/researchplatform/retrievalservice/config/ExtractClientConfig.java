package com.comeback.researchplatform.retrievalservice.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class ExtractClientConfig {

    @Bean
    public RestClient extractorRestClient(ExtractProperties props) {
        // Pinned to HTTP/1.1. The JDK client otherwise offers an h2c upgrade on plaintext
        // (Connection: Upgrade + Upgrade: h2c), and uvicorn's h11 reads that as a protocol
        // switch and never consumes the request body — FastAPI then rejects it as missing.
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(http))
                .build();
    }
}
