package com.comeback.researchplatform.agentservice.retrieval;

import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractedDocument;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractResponse;
import com.comeback.researchplatform.agentservice.retrieval.dto.SearchResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Replays recorded search/extract responses instead of calling
 * retrieval-service -- PLAN's fixture mode, zero cost, deterministic, no
 * network. A miss throws {@link com.comeback.researchplatform.agentservice.fixtures.FixtureMissException}
 * rather than returning an empty result -- a silent empty response would
 * make a real gap in the fixture set look like a legitimate "no results
 * found" answer.
 */
@Component
@Profile("fixture")
public class FixtureRetrievalClient implements RetrievalClient {

    private final FixtureIO fixtureIO;

    public FixtureRetrievalClient(FixtureIO fixtureIO) {
        this.fixtureIO = fixtureIO;
    }

    @Override
    public SearchResponse search(List<String> queries, int maxResults, String freshness, int minTier) {
        return fixtureIO.read("search-responses.json", FixtureIO.keyFor(String.join("|", queries)), SearchResponse.class);
    }

    @Override
    public ExtractResponse extract(List<String> urls) {
        List<ExtractedDocument> documents = urls.stream()
                .map(url -> fixtureIO.read("extract-responses.json", FixtureIO.keyFor(url), ExtractedDocument.class))
                .toList();
        return new ExtractResponse(documents);
    }
}
