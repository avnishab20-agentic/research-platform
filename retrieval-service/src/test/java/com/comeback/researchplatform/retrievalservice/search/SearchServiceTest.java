package com.comeback.researchplatform.retrievalservice.search;

import com.comeback.researchplatform.retrievalservice.config.SourceTierProperties;
import com.comeback.researchplatform.retrievalservice.dto.SearchRequest;
import com.comeback.researchplatform.retrievalservice.dto.SearchResponse;
import com.comeback.researchplatform.retrievalservice.quota.QuotaExceededException;
import com.comeback.researchplatform.retrievalservice.quota.QuotaService;
import com.comeback.researchplatform.retrievalservice.tier.SourceTierResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * SearXNG is faked with MockRestServiceServer and Redis with Mockito, so the whole
 * cache-aside path runs without a container. The tier resolver and the ObjectMapper are
 * real — they are pure logic, and faking them would only test the fakes.
 */
class SearchServiceTest {

    private static final String BASE_URL = "http://searxng.test";

    private MockRestServiceServer server;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private QuotaService quotaService;
    private ObjectMapper objectMapper;
    private SearchService searchService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();

        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        // Default: this caller wins the single-flight lock, which is the path every
        // pre-existing test was written against. Unstubbed, setIfAbsent returns null and
        // every test would silently take the loser path and poll for five seconds.
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        quotaService = mock(QuotaService.class);
        objectMapper = new ObjectMapper();

        SourceTierResolver tierResolver = new SourceTierResolver(new SourceTierProperties(
                List.of("rbi.org.in"),
                List.of("thehindu.com"),
                List.of("*.blogspot.*")));

        searchService = new SearchService(
                tierResolver, builder.build(), RestClient.create(), "", redis, objectMapper, quotaService);
    }

    /**
     * @param encodedQuery the query exactly as it appears on the wire. Note this is the
     *                     <em>raw</em> request query, URL-encoded — normalization applies
     *                     only to the cache key, never to what SearXNG is asked.
     */
    private void searxngReturns(String encodedQuery, SearxngResult... results) {
        server.expect(requestTo(BASE_URL + "/search?q=" + encodedQuery + "&format=json"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(new SearxngSearchResponse(List.of(results)))));
    }

    private static SearchRequest request(String query, int maxResults, int minTier, String freshness) {
        return new SearchRequest(List.of(query), maxResults, freshness, minTier);
    }

    @Test
    void aCacheMissCallsUpstreamSpendsOneCreditAndStoresTheResultFor24Hours() {
        when(valueOps.get(anyString())).thenReturn(null);
        searxngReturns("rbi", new SearxngResult("https://thehindu.com/a", "T", "snippet"));

        SearchResponse response = searchService.search(request("rbi", 10, 4, null));

        assertThat(response.creditsSpent()).isEqualTo(1);
        assertThat(response.cacheHits()).isZero();
        assertThat(response.results()).hasSize(1);
        server.verify();

        verify(valueOps).set(anyString(), anyString(), eq(Duration.ofHours(24)));
        verify(quotaService).recordSpend();
    }

    @Test
    void aQuotaBreachStillReleasesTheLock() {
        // The budget check runs while this caller holds the single-flight lock. If the
        // breach skipped the release, every other caller would wait out the 30s lock TTL.
        when(valueOps.get(anyString())).thenReturn(null);
        doThrow(new QuotaExceededException(1000)).when(quotaService).checkBudget();

        assertThatThrownBy(() -> searchService.search(request("rbi", 10, 4, null)))
                .isInstanceOf(QuotaExceededException.class);

        verify(redis).delete(startsWith("lock:"));
        server.verify();
    }

    @Test
    void anEmptyResultIsNeverCached() {
        // A CAPTCHA'd engine returns zero results; caching that blinds the query for 24h.
        when(valueOps.get(anyString())).thenReturn(null);
        searxngReturns("rbi");

        assertThat(searchService.search(request("rbi", 10, 4, null)).results()).isEmpty();

        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void aCacheHitCostsNothingAndNeverTouchesUpstream() {
        String cached = objectMapper.writeValueAsString(
                List.of(new SearxngResult("https://thehindu.com/a", "T", "snippet")));
        when(valueOps.get(anyString())).thenReturn(cached);

        SearchResponse response = searchService.search(request("rbi", 10, 4, null));

        assertThat(response.cacheHits()).isEqualTo(1);
        assertThat(response.creditsSpent()).isZero();
        assertThat(response.results()).hasSize(1);

        // No expectation was registered on the server, so any upstream call would fail
        // the test. This is the assertion behind PLAN's "2nd identical query costs 0".
        server.verify();
        verify(quotaService, never()).recordSpend();
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void resultsWorseThanTheRequestedTierAreDropped() {
        when(valueOps.get(anyString())).thenReturn(null);
        searxngReturns("rbi",
                new SearxngResult("https://rbi.org.in/official", "gov", "s"),   // tier 1
                new SearxngResult("https://thehindu.com/news", "paper", "s"),   // tier 2
                new SearxngResult("https://someblog.com/post", "blog", "s"));   // tier 3

        SearchResponse response = searchService.search(request("rbi", 10, 2, null));

        assertThat(response.results())
                .extracting(r -> r.url())
                .containsExactly("https://rbi.org.in/official", "https://thehindu.com/news");
    }

    @Test
    void maxResultsIsAppliedClientSideBecauseSearxngIgnoresIt() {
        when(valueOps.get(anyString())).thenReturn(null);
        searxngReturns("rbi",
                new SearxngResult("https://thehindu.com/1", "a", "s"),
                new SearxngResult("https://thehindu.com/2", "b", "s"),
                new SearxngResult("https://thehindu.com/3", "c", "s"));

        SearchResponse response = searchService.search(request("rbi", 2, 4, null));

        assertThat(response.results()).hasSize(2);
    }

    @Test
    void theSnippetComesFromSearxngsContentField() {
        when(valueOps.get(anyString())).thenReturn(null);
        searxngReturns("rbi", new SearxngResult("https://thehindu.com/a", "Title", "The snippet"));

        SearchResponse response = searchService.search(request("rbi", 10, 4, null));

        // SearXNG calls it "content"; our API calls it "snippet". If that mapping breaks,
        // every search result silently loses its preview text.
        assertThat(response.results().get(0).snippet()).isEqualTo("The snippet");
        assertThat(response.results().get(0).title()).isEqualTo("Title");
    }

    @Test
    void queryNormalizationMakesSpacingAndCaseIrrelevantToTheCacheKey() {
        when(valueOps.get(anyString())).thenReturn(null);
        searxngReturns("rbi", new SearxngResult("https://thehindu.com/a", "T", "s"));
        // The second call goes upstream with the query exactly as the caller typed it:
        // trim/lowercase/whitespace-collapse shape the cache key only.
        searxngReturns("%20%20RBI%20%20", new SearxngResult("https://thehindu.com/a", "T", "s"));

        searchService.search(request("rbi", 10, 4, null));
        searchService.search(request("  RBI  ", 10, 4, null));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOps, org.mockito.Mockito.times(2)).get(keys.capture());

        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
    }

    @Test
    void freshnessIsPartOfTheCacheKeySoTwoRangesCannotCollide() {
        when(valueOps.get(anyString())).thenReturn(null);
        searxngReturns("rbi", new SearxngResult("https://thehindu.com/a", "T", "s"));
        searxngReturns("rbi", new SearxngResult("https://thehindu.com/a", "T", "s"));

        searchService.search(request("rbi", 10, 4, "day"));
        searchService.search(request("rbi", 10, 4, "month"));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOps, org.mockito.Mockito.times(2)).get(keys.capture());

        // Same words, different time range. freshness is not wired through to SearXNG
        // yet, so this collision is currently latent — it goes live the moment anyone
        // wires time_range, and this test is what stops it being reintroduced.
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
    }

    @Test
    void theCacheKeyIsVersionedAndProviderScoped() {
        when(valueOps.get(anyString())).thenReturn(null);
        searxngReturns("rbi", new SearxngResult("https://thehindu.com/a", "T", "s"));

        searchService.search(request("rbi", 10, 4, null));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOps).get(key.capture());

        // Version bump is how a key-format change is rolled out; the provider segment is
        // there so adding a second search engine later doesn't force a cache-wide flush.
        assertThat(key.getValue()).startsWith("search:v2:searxng:");
        assertThat(key.getValue()).hasSize("search:v2:searxng:".length() + 16);
    }
}
