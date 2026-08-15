package com.comeback.researchplatform.retrievalservice.dto;

import java.util.List;

public record SearchResponse(List<SearchResult> results , int creditsSpent, int cacheHits){
}
