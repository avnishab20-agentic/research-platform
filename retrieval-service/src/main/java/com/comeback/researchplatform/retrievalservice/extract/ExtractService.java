package com.comeback.researchplatform.retrievalservice.extract;

import com.comeback.researchplatform.retrievalservice.config.ExtractProperties;
import com.comeback.researchplatform.retrievalservice.dto.Document;
import com.comeback.researchplatform.retrievalservice.dto.ExtractRequest;
import com.comeback.researchplatform.retrievalservice.dto.ExtractResponse;
import com.comeback.researchplatform.retrievalservice.tier.SourceTierResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.comeback.researchplatform.retrievalservice.config.RateLimitProperties;
import com.comeback.researchplatform.retrievalservice.ratelimit.DomainRateLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cache-aside around fetch + extract, one URL per virtual thread.
 * <p>
 * The single-flight lock and the extractor {@code Semaphore(4)} are PLAN Session 4
 * and land later.
 */
@Service
public class ExtractService {

    private static final Logger log = LoggerFactory.getLogger(ExtractService.class);

    private final PageFetcher pageFetcher;
    private final ExtractorClient extractorClient;
    private final SourceTierResolver sourceTierResolver;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration cacheTtl;
    private final RateLimitProperties rateLimitProperties;
    private final DomainRateLimiter domainRateLimiter;

    public ExtractService(PageFetcher pageFetcher,
                          ExtractorClient extractorClient,
                          SourceTierResolver sourceTierResolver,
                          StringRedisTemplate redis,
                          ObjectMapper objectMapper,
                          ExtractProperties props,
                          RateLimitProperties rateLimitProperties,
                          DomainRateLimiter domainRateLimiter) {
        this.pageFetcher = pageFetcher;
        this.extractorClient = extractorClient;
        this.sourceTierResolver = sourceTierResolver;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.cacheTtl = props.cacheTtl();
        this.rateLimitProperties = rateLimitProperties;
        this.domainRateLimiter = domainRateLimiter;
    }

    public ExtractResponse extract(ExtractRequest request) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Document>> futures = new ArrayList<>();
            for (String url : request.urls()) {
                futures.add(CompletableFuture
                        .supplyAsync(() -> extractOne(url), executor)
                        // extractOne is written not to throw, but Redis and Jackson can.
                        // Without this, one throw fails the whole batch on join().
                        .exceptionally(e -> {
                            log.warn("extract failed for {}", url, e);
                            return new Document(url, null, sourceTierResolver.resolveTier(url), "UNREACHABLE");
                        }));
            }
            // Second pass on purpose: joining inside the submit loop would serialise it.
            return new ExtractResponse(futures.stream().map(CompletableFuture::join).toList());
        }
    }

    private Document extractOne(String url) {
        String key = ExtractCacheKey.of(url);

        String cachedJson = redis.opsForValue().get(key);
        if (cachedJson != null) {
            return objectMapper.readValue(cachedJson, Document.class);
        }

        if (!awaitToken(url)) {
            // Not cached: being throttled is a fact about this moment, not about the page.
            return new Document(url, null, sourceTierResolver.resolveTier(url), "RATE_LIMITED");
        }

        FetchedPage page = pageFetcher.fetch(url);
        if (!page.isOk()) {
            // UNREACHABLE / TOO_LARGE are not cached: a timeout is a fact about this
            // moment, not about the page, and caching it would blind us for 7 days.
            return new Document(url, null, sourceTierResolver.resolveTier(url), page.status());
        }

        ExtractorResult result;
        try {
            result = extractorClient.extract(url, page.html());
        } catch (Exception e) {
            // The sidecar being down is our outage, not the page's. Don't cache it.
            // Logged because the returned status can't distinguish it from a dead page.
            log.warn("extractor call failed for {}", url, e);
            return new Document(url, null, sourceTierResolver.resolveTier(url), "UNREACHABLE");
        }
        if (result == null) {
            return new Document(url, null, sourceTierResolver.resolveTier(url), "UNREACHABLE");
        }

        Document document = new Document(url, result.text(),
                sourceTierResolver.resolveTier(url), result.status());

        // OK and PAYWALLED are both stable observations about the page itself, so both cache.
        redis.opsForValue().set(key, objectMapper.writeValueAsString(document), cacheTtl);
        return document;
    }
    /**
     * Waits for this domain's token bucket to hand over a token.
     * <p>
     * Waiting rather than failing fast: the limit exists to be polite to the origin, and
     * dropping a good URL to enforce politeness makes the research worse for no gain. The
     * attempt cap is what stops a permanently busy domain parking this thread forever.
     */
    private boolean awaitToken(String url) {
        // How long one token takes to appear. Derived, not hardcoded: at refill-rate 0.5
        // a token needs 2s, and a fixed 1s sleep would burn every attempt on nothing.
        long waitMillis = (long) (1000 / rateLimitProperties.refillRate());

        for (int attempt = 0; attempt < rateLimitProperties.maxWaitAttempts(); attempt++) {
            if (domainRateLimiter.tryAcquire(url)) {
                return true;
            }
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException e) {
                // An interrupt means "stop, we're shutting down". Swallowing it silently
                // would leave this loop sleeping through the shutdown, so restore the flag
                // and give up.
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
