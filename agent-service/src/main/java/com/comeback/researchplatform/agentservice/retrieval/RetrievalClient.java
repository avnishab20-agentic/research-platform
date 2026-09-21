package com.comeback.researchplatform.agentservice.retrieval;

import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractResponse;
import com.comeback.researchplatform.agentservice.retrieval.dto.SearchResponse;

import java.util.List;

/**
 * Everything the Researcher/Critic need from retrieval-service. Two
 * implementations: {@link HttpRetrievalClient} (real, over HTTP -- default)
 * and {@link FixtureRetrievalClient} (replays recorded JSON, active under the
 * "fixture" profile -- PLAN's "all three evals depend on this" fixture mode).
 * ResearcherService/CriticService depend on this interface, never on a
 * concrete implementation, so the fixture swap is invisible to them.
 */
public interface RetrievalClient {

    SearchResponse search(List<String> queries, int maxResults, String freshness, int minTier);

    ExtractResponse extract(List<String> urls);
}
