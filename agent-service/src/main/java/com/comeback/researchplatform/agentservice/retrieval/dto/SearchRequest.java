package com.comeback.researchplatform.agentservice.retrieval.dto;

import java.util.List;

/** Mirrors retrieval-service's own SearchRequest -- same field names, so
 *  Jackson serializes/deserializes identically across the HTTP boundary
 *  without the two services sharing a Java type. */
public record SearchRequest(List<String> queries, int maxResults, String freshness, int minTier) {}
