package com.comeback.researchplatform.retrievalservice.search;

import com.comeback.researchplatform.retrievalservice.dto.SearchRequest;
import com.comeback.researchplatform.retrievalservice.dto.SearchResponse;
import com.comeback.researchplatform.retrievalservice.dto.SearchResult;
import com.comeback.researchplatform.retrievalservice.hash.Hashing;
import com.comeback.researchplatform.retrievalservice.quota.QuotaService;
import com.comeback.researchplatform.retrievalservice.tier.SourceTierResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs web searches with a Redis cache in front.
 * <p>
 * For each query: use the cache if it has the answer. If not, exactly one caller
 * (whoever grabs the lock) pays for the real search and fills the cache; anyone
 * else asking the same thing at the same moment waits for that cache entry.
 */
@Service
public class SearchService {

    private static final String LOCK_PREFIX = "lock:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    private static final Duration SEARCH_TTL = Duration.ofHours(24);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final int MAX_POLL_ATTEMPTS = 50;

    private final SourceTierResolver sourceTierResolver;
    private final RestClient searxngRestClient;
    private final RestClient tavilyRestClient;
    // Tavily when a key is configured, SearXNG otherwise. Part of the cache key,
    // so switching providers never serves one provider's results as the other's.
    private final String provider;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final QuotaService quotaService;

    public SearchService(SourceTierResolver sourceTierResolver,
                         @Qualifier("searxngRestClient") RestClient searxngRestClient,
                         @Qualifier("tavilyRestClient") RestClient tavilyRestClient,
                         @Value("${tavily.api-key:}") String tavilyApiKey,
                         StringRedisTemplate stringRedisTemplate,
                         ObjectMapper objectMapper,
                         QuotaService quotaService) {
        this.sourceTierResolver = sourceTierResolver;
        this.searxngRestClient = searxngRestClient;
        this.tavilyRestClient = tavilyRestClient;
        this.provider = tavilyApiKey.isBlank() ? "searxng" : "tavily";
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.quotaService = quotaService;
    }

    public SearxngSearchResponse fetchResults(String query) {
        if (provider.equals("tavily")) {
            return tavilyRestClient.post()
                    .uri("/search")
                    .body(Map.of("query", query, "max_results", 10))
                    .retrieve()
                    .body(SearxngSearchResponse.class);
        }
        return searxngRestClient.get()
                .uri("/search?q={query}&format=json", query)
                .retrieve()
                .body(SearxngSearchResponse.class);
    }

    public SearchResponse search(SearchRequest request) {
        List<SearxngResult> rawResults = new ArrayList<>();
        int creditsSpent = 0;
        int cacheHits = 0;

        for (String query : request.queries()) {
            String key = cacheKey(request, query);

            // 1. Already cached: free.
            String cachedJson = stringRedisTemplate.opsForValue().get(key);
            if (cachedJson != null) {
                rawResults.addAll(readResults(cachedJson));
                cacheHits++;
                continue;
            }

            // 2. Not cached, and we got the lock: we are the one caller that pays.
            String lockKey = LOCK_PREFIX + key;
            Boolean gotLock = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL);
            if (Boolean.TRUE.equals(gotLock)) {
                try {
                    rawResults.addAll(paidFetch(key, query));
                } finally {
                    // finally, or a failed fetch parks everyone else for the full TTL.
                    stringRedisTemplate.delete(lockKey);
                }
                creditsSpent++;
                continue;
            }

            // 3. Someone else holds the lock: wait for them to fill the cache.
            String waitedJson = awaitCachedValue(key);
            if (waitedJson != null) {
                rawResults.addAll(readResults(waitedJson));
                cacheHits++;
                continue;
            }

            // 4. They never did (crashed or too slow). Fail open: a duplicate fetch is
            //    a better outcome than returning nothing for this query.
            rawResults.addAll(paidFetch(key, query));
            creditsSpent++;
        }

        // Label each result with its source tier, drop the ones below the requested
        // tier, and stop at maxResults (SearXNG ignores any "max results" setting).
        List<SearchResult> results = new ArrayList<>();
        for (SearxngResult raw : rawResults) {
            if (results.size() == request.maxResults()) {
                break;
            }
            int tier = sourceTierResolver.resolveTier(raw.url());
            if (tier <= request.minTier()) {
                results.add(new SearchResult(raw.url(), raw.title(), raw.content(), tier));
            }
        }
        return new SearchResponse(results, creditsSpent, cacheHits);
    }

    /** A real upstream search, which costs one credit. The budget is checked
     *  first, so a breach stops the spend instead of only counting it. */
    private List<SearxngResult> paidFetch(String key, String query) {
        quotaService.checkBudget();
        List<SearxngResult> results = fetchAndCache(key, query);
        quotaService.recordSpend();
        return results;
    }

    private List<SearxngResult> readResults(String json) {
        return objectMapper.readValue(json, new TypeReference<List<SearxngResult>>() {});
    }

    private List<SearxngResult> fetchAndCache(String key, String query) {
        List<SearxngResult> results = fetchResults(query).results();
        if (results == null || results.isEmpty()) {
            // Never cache an empty list: a CAPTCHA'd or throttled engine returns zero results,
            // and caching that blinds the query for 24h after the engine recovers. The waiters
            // then time out and fetch themselves -- a duplicate spend, but only on failure.
            return List.of();
        }
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

    private String cacheKey(SearchRequest request, String query) {
        String freshness = request.freshness() == null ? "" : request.freshness();
        String raw = normalizeQuery(query) + "|" + freshness;
        // v2: v1 holds empty lists cached while SearXNG was being CAPTCHA'd.
        return "search:v2:" + provider + ":" + Hashing.sha256Hex(raw).substring(0, 16);
    }

    private static String normalizeQuery(String query) {
        return query.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }
}
