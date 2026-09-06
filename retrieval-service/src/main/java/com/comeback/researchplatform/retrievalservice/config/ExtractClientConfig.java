package com.comeback.researchplatform.retrievalservice.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ExtractClientConfig {

    @Bean
    public RestClient extractorRestClient(ExtractProperties props) {
        // The request factory is NOT optional here, and leaving it to the default cost us
        // four sessions of "/extract has never been run live".
        //
        // Spring's default builds on the JDK's HttpClient, which opens every cleartext
        // request with "Upgrade: h2c" — an offer to switch to HTTP/2 — and sends the body
        // chunked. uvicorn (h11) does not speak h2c: it logs "Unsupported upgrade request",
        // drops the body on the floor, and hands FastAPI an empty request. FastAPI then
        // answers 422 {"loc":["body"],"msg":"Field required"}, RestClient turns the 422 into
        // an exception, and ExtractService catches it and reports the page UNREACHABLE — so
        // a perfectly reachable page came back as a network failure.
        //
        // SimpleClientHttpRequestFactory is plain HttpURLConnection: HTTP/1.1, no upgrade
        // offer, Content-Length body. It is the same factory PageFetchClientConfig already
        // uses, which is exactly why fetching worked while extracting did not.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());

        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
