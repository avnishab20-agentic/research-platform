package com.comeback.researchplatform.retrievalservice.dto;

import java.util.List;


public record SearchRequest(List<String> queries, int maxResults, String freshness, int minTier) {

}
