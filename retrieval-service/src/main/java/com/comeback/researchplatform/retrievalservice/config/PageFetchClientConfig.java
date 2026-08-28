package com.comeback.researchplatform.retrievalservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;


@Configuration
public class PageFetchClientConfig {

    @Bean
    public RestClient pageFetchRestClient(ExtractProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());

        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", "research-platform/0.1")
                .build();
    }


}
