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

    private static final String LOCK_PREFIX = "lock:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    private static final Duration SEARCH_TTL = Duration.ofHours(24);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final int MAX_POLL_ATTEMPTS = 50;

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
                queryResults = readResults(cachedJson);
                cacheHits++;
            }else {
                String lockKey = LOCK_PREFIX + key;

                if (Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                        .setIfAbsent(lockKey, "1", LOCK_TTL))) {
                    // We hold the lock: we are the one caller that pays. Checked before the
                    // real fetch, not after -- a breach must stop the spend, not just count it.
                    quotaService.checkBudget();
                    try {
                        queryResults = fetchAndCache(key, query);
                    } finally {
                        // finally, or a SearXNG timeout parks everyone else for the full TTL.
                        stringRedisTemplate.delete(lockKey);
                    }
                    creditsSpent++;
                    quotaService.recordSpend();
                } else {
                    String json = awaitCachedValue(key);
                    if (json != null) {
                        queryResults = readResults(json);
                        cacheHits++;
                    } else {
                        // Fail open. The holder crashed or overran; a duplicate fetch is a
                        // better outcome than returning nothing for this query.
                        quotaService.checkBudget();
                        queryResults = fetchAndCache(key, query);
                        creditsSpent++;
                        quotaService.recordSpend();
                    }
                }
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

    private List<SearxngResult> readResults(String json) {
        return objectMapper.readValue(json, new TypeReference<List<SearxngResult>>() {});
    }

    private List<SearxngResult> fetchAndCache(String key, String query) {
        List<SearxngResult> results = fetchResults(query).results();
        // Cache written before the lock is released, or the waiters wake to an empty cache.
        stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(results), SEARCH_TTL);
        return results;
    }

    /** Waits for the lock holder to publish the cache entry. Null means it never showed up. */
    private String awaitCachedValue(String key) {
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json != null) {
                return json;
            }
        }
        return null;
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
