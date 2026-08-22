package com.comeback.researchplatform.retrievalservice.search;

import com.comeback.researchplatform.retrievalservice.dto.SearchRequest;
import com.comeback.researchplatform.retrievalservice.dto.SearchResult;
import com.comeback.researchplatform.retrievalservice.dto.SearchResponse;
import com.comeback.researchplatform.retrievalservice.hash.Hashing;
import com.comeback.researchplatform.retrievalservice.tier.SourceTierResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.comeback.researchplatform.retrievalservice.quota.QuotaService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;


import java.util.List;

@Service
public class SearchService {

    private final SourceTierResolver sourceTierResolver;
    private final RestClient searxngRestClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final QuotaService quotaService;


    public SearchService(SourceTierResolver sourceTierResolver,  @Qualifier("searxngRestClient") RestClient searxngRestClient , StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper , QuotaService quotaService) {
        this.sourceTierResolver = sourceTierResolver;
        this.searxngRestClient = searxngRestClient;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.quotaService = quotaService;
    }

    public SearxngSearchResponse fetchResults(String query){
        return searxngRestClient.get()
                .uri("/search?q={query}&format=json", query)
                .retrieve()
                .body(SearxngSearchResponse.class);
    }

    public SearchResponse search(SearchRequest request){
        List<SearxngResult> rawResults = new ArrayList<>();
        int creditsSpent = 0;
        int cacheHits = 0;
        for (String query : request.queries()){
            String key= cacheKey(request,query);
            String cachedJson = stringRedisTemplate.opsForValue().get(key);

            List<SearxngResult> queryResults;
            if (cachedJson != null) {
                queryResults = objectMapper.readValue(cachedJson, new TypeReference<List<SearxngResult>>() {
                });
                cacheHits++;
            }else {
                queryResults=fetchResults(query).results();
                stringRedisTemplate.opsForValue().set(key,objectMapper.writeValueAsString(queryResults), Duration.ofHours(24));
                creditsSpent++;
                quotaService.recordSpend();
            }
            rawResults.addAll(queryResults);
        }
        List<SearchResult> results  = rawResults.stream()
                .map(r -> new SearchResult(r.url(),r.title(),r.content(),sourceTierResolver.resolveTier(r.url())))
                .filter(r -> r.tier() <=request.minTier())
                .limit(request.maxResults())
                .toList();
        return new SearchResponse(results, creditsSpent, cacheHits);
    }

private static String cacheKey(SearchRequest request , String query){
        String freshness = request.freshness() == null ?"" : request.freshness();
        String raw = normalizeQuery(query) + "|" +freshness;
        return "search:v1:searxng:" + Hashing.sha256Hex(raw).substring(0,16);
    }

private static String normalizeQuery( String query){
        return query.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
}




}
