package com.comeback.researchplatform.retrievalservice.extract;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;





@Component
public class ExtractorClient  {

    private final RestClient extractorRestClient;

    public ExtractorClient(@Qualifier("extractorRestClient") RestClient extractorRestClient) {
        this.extractorRestClient = extractorRestClient;
    }


    public ExtractorResult extract(String url, String html) {
        return extractorRestClient.post()
                .uri("/extract")
                .body(new ExtractorRequest(url, html))
                .retrieve()
                .body(ExtractorResult.class);
    }
}
