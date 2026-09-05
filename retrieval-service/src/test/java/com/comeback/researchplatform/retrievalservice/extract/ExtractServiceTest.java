package com.comeback.researchplatform.retrievalservice.extract;

import com.comeback.researchplatform.retrievalservice.config.ExtractProperties;
import com.comeback.researchplatform.retrievalservice.config.SourceTierProperties;
import com.comeback.researchplatform.retrievalservice.dto.Document;
import com.comeback.researchplatform.retrievalservice.dto.ExtractRequest;
import com.comeback.researchplatform.retrievalservice.dto.ExtractResponse;
import com.comeback.researchplatform.retrievalservice.tier.SourceTierResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The interesting behaviour here is which outcomes get cached and which don't, so most
 * of these tests assert on whether Redis was written to at all.
 */
class ExtractServiceTest {

    private static final Duration TTL = Duration.ofDays(7);
    private static final String URL = "https://thehindu.com/news/rbi";

    private PageFetcher pageFetcher;
    private ExtractorClient extractorClient;
    private ValueOperations<String, String> valueOps;
    private ObjectMapper objectMapper;
    private ExtractService extractService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        pageFetcher = mock(PageFetcher.class);
        extractorClient = mock(ExtractorClient.class);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        objectMapper = new ObjectMapper();

        SourceTierResolver tierResolver = new SourceTierResolver(new SourceTierProperties(
                List.of("rbi.org.in"),
                List.of("thehindu.com"),
                List.of("*.blogspot.*")));

        ExtractProperties props = new ExtractProperties(
                "http://localhost:8000", 204800, TTL, Duration.ofSeconds(5), Duration.ofSeconds(10));

        extractService = new ExtractService(
                pageFetcher, extractorClient, tierResolver, redis, objectMapper, props);
    }

    private Document extractOne(String url) {
        ExtractResponse response = extractService.extract(new ExtractRequest(List.of(url)));
        return response.documents().get(0);
    }

    @Test
    void aCachedDocumentIsReturnedWithoutFetchingAnything() {
        Document cached = new Document(URL, "cached body", 2, "OK");
        when(valueOps.get(anyString())).thenReturn(objectMapper.writeValueAsString(cached));

        Document document = extractOne(URL);

        assertThat(document.text()).isEqualTo("cached body");
        verify(pageFetcher, never()).fetch(anyString());
        verify(extractorClient, never()).extract(anyString(), anyString());
    }

    @Test
    void aSuccessfulExtractionIsCachedForSevenDays() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(pageFetcher.fetch(URL)).thenReturn(new FetchedPage("<html>body</html>", "OK"));
        when(extractorClient.extract(URL, "<html>body</html>"))
                .thenReturn(new ExtractorResult(URL, "Title", "clean text", null, "OK"));

        Document document = extractOne(URL);

        assertThat(document.text()).isEqualTo("clean text");
        assertThat(document.status()).isEqualTo("OK");
        assertThat(document.tier()).isEqualTo(2);
        verify(valueOps).set(anyString(), anyString(), eq(TTL));
    }

    @Test
    void paywalledIsCachedToo() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(pageFetcher.fetch(URL)).thenReturn(new FetchedPage("<html>teaser</html>", "OK"));
        when(extractorClient.extract(anyString(), anyString()))
                .thenReturn(new ExtractorResult(URL, "Title", "teaser", null, "PAYWALLED"));

        Document document = extractOne(URL);

        // PAYWALLED is a stable fact about the page, same as OK — the paywall will still
        // be there in an hour, so re-fetching would waste a request to learn nothing.
        assertThat(document.status()).isEqualTo("PAYWALLED");
        verify(valueOps).set(anyString(), anyString(), eq(TTL));
    }

    @Test
    void anUnreachablePageIsNeverCached() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(pageFetcher.fetch(URL)).thenReturn(new FetchedPage(null, "UNREACHABLE"));

        Document document = extractOne(URL);

        // A timeout is a fact about this moment, not about the page. Caching it would
        // blind us to a perfectly healthy URL for seven days.
        assertThat(document.status()).isEqualTo("UNREACHABLE");
        assertThat(document.text()).isNull();
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        verify(extractorClient, never()).extract(anyString(), anyString());
    }

    @Test
    void anOversizedPageIsNeverCached() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(pageFetcher.fetch(URL)).thenReturn(new FetchedPage(null, "TOO_LARGE"));

        Document document = extractOne(URL);

        assertThat(document.status()).isEqualTo("TOO_LARGE");
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void aDeadSidecarBecomesUnreachableRatherThanAnException() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(pageFetcher.fetch(URL)).thenReturn(new FetchedPage("<html>body</html>", "OK"));
        when(extractorClient.extract(anyString(), anyString()))
                .thenThrow(new RuntimeException("connection refused"));

        Document document = extractOne(URL);

        // Slightly dishonest — the page was reachable, our sidecar wasn't — but the enum
        // has no better value and inventing one is scope creep. Crucially: not cached.
        assertThat(document.status()).isEqualTo("UNREACHABLE");
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void oneDeadUrlDoesNotSinkTheRestOfTheBatch() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(pageFetcher.fetch("https://thehindu.com/dead"))
                .thenReturn(new FetchedPage(null, "UNREACHABLE"));
        when(pageFetcher.fetch("https://thehindu.com/alive"))
                .thenReturn(new FetchedPage("<html>body</html>", "OK"));
        when(extractorClient.extract(eq("https://thehindu.com/alive"), anyString()))
                .thenReturn(new ExtractorResult(
                        "https://thehindu.com/alive", "T", "clean text", null, "OK"));

        ExtractResponse response = extractService.extract(new ExtractRequest(
                List.of("https://thehindu.com/dead", "https://thehindu.com/alive")));

        assertThat(response.documents()).hasSize(2);
        assertThat(response.documents().get(0).status()).isEqualTo("UNREACHABLE");
        assertThat(response.documents().get(1).status()).isEqualTo("OK");
        assertThat(response.documents().get(1).text()).isEqualTo("clean text");
    }

    @Test
    void fourSpellingsOfOneArticleShareASingleCacheEntry() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(pageFetcher.fetch(anyString())).thenReturn(new FetchedPage("<html>b</html>", "OK"));
        when(extractorClient.extract(anyString(), anyString()))
                .thenReturn(new ExtractorResult(URL, "T", "text", null, "OK"));

        extractService.extract(new ExtractRequest(List.of(
                "https://www.TheHindu.com/news/rbi/?utm_source=twitter#top",
                "https://thehindu.com/news/rbi")));

        org.mockito.ArgumentCaptor<String> keys = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps, org.mockito.Mockito.times(2)).get(keys.capture());

        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
    }
}
