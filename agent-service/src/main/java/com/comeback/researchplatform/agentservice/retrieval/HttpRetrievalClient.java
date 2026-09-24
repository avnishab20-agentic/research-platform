package com.comeback.researchplatform.agentservice.retrieval;

import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractRequest;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractResponse;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractedDocument;
import com.comeback.researchplatform.agentservice.retrieval.dto.SearchRequest;
import com.comeback.researchplatform.agentservice.retrieval.dto.SearchResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * The real implementation -- calls retrieval-service over HTTP, per
 * CLAUDE.md's "agents -> retrieval-service = HTTP" rule. Active whenever the
 * "fixture" profile is not (the ordinary case).
 * <p>
 * Optionally records every response verbatim when {@code
 * fixtures.record-mode: true} -- a normal live run becomes the way fixture
 * data gets captured, rather than hand-authoring JSON. Recording never
 * changes what's returned to the caller; it's a side effect on the way out.
 */
@Component
@Profile("!fixture")
public class HttpRetrievalClient implements RetrievalClient {

    private final RestClient restClient;
    private final FixtureIO fixtureIO;

    public HttpRetrievalClient(RestClient retrievalRestClient, FixtureIO fixtureIO) {
        this.restClient = retrievalRestClient;
        this.fixtureIO = fixtureIO;
    }

    @Override
    public SearchResponse search(List<String> queries, int maxResults, String freshness, int minTier) {
        SearchResponse response = restClient.post()
                .uri("/api/v1/search")
                .body(new SearchRequest(queries, maxResults, freshness, minTier))
                .retrieve()
                .body(SearchResponse.class);
        if (fixtureIO.isRecordMode()) {
            fixtureIO.record("search-responses.json", FixtureIO.keyFor(String.join("|", queries)), response);
        }
        return response;
    }

    @Override
    public ExtractResponse extract(List<String> urls) {
        ExtractResponse response = restClient.post()
                .uri("/api/v1/extract")
                .body(new ExtractRequest(urls))
                .retrieve()
                .body(ExtractResponse.class);
        if (fixtureIO.isRecordMode()) {
            // One entry per URL, not per batch -- a fixture question set built
            // from several recorded runs will re-use the same URL across
            // different batches, and per-URL keys let those share one entry.
            for (ExtractedDocument doc : response.documents()) {
                fixtureIO.record("extract-responses.json", FixtureIO.keyFor(doc.url()), doc);
            }
        }
        return response;
    }
}
