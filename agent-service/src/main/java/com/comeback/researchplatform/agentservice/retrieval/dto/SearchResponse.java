package com.comeback.researchplatform.agentservice.retrieval.dto;

import java.util.List;

public record SearchResponse(List<SearchResult> results, int creditsSpent, int cacheHits) {}
