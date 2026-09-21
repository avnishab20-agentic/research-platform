package com.comeback.researchplatform.agentservice.retrieval;

import com.comeback.researchplatform.agentservice.retrieval.dto.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Everything the Researcher needs from retrieval-service, over HTTP -- per
 * CLAUDE.md's "agents -> retrieval-service = HTTP" rule (Kafka is for
 * long-running/durable hops, this is a cache lookup). retrieval-service is
 * the only thing that touches the open web; this client never calls SearXNG
 * or fetches a page directly.
 */
@Component
public class RetrievalClient {

    private final RestClient restClient;

    public RetrievalClient(RestClient retrievalRestClient) {
        this.restClient = retrievalRestClient;
    }

    public SearchResponse search(List<String> queries, int maxResults, String freshness, int minTier) {
        return restClient.post()
                .uri("/api/v1/search")
                .body(new SearchRequest(queries, maxResults, freshness, minTier))
                .retrieve()
                .body(SearchResponse.class);
    }

    public ExtractResponse extract(List<String> urls) {
        return restClient.post()
                .uri("/api/v1/extract")
                .body(new ExtractRequest(urls))
                .retrieve()
                .body(ExtractResponse.class);
    }
}
