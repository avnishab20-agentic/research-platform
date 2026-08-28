package com.comeback.researchplatform.retrievalservice.extract;

import com.comeback.researchplatform.retrievalservice.config.ExtractProperties;
import com.comeback.researchplatform.retrievalservice.dto.Document;
import com.comeback.researchplatform.retrievalservice.dto.ExtractRequest;
import com.comeback.researchplatform.retrievalservice.dto.ExtractResponse;
import com.comeback.researchplatform.retrievalservice.tier.SourceTierResolver;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Cache-aside around fetch + extract, one URL at a time.
 * <p>
 * Parallelism is deliberately absent here — {@code CompletableFuture} fan-out, the
 * per-domain token bucket and the single-flight lock are PLAN Session 4 and land later.
 */
@Service
public class ExtractService {

    private final PageFetcher pageFetcher;
    private final ExtractorClient extractorClient;
    private final SourceTierResolver sourceTierResolver;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration cacheTtl;

    public ExtractService(PageFetcher pageFetcher,
                          ExtractorClient extractorClient,
                          SourceTierResolver sourceTierResolver,
                          StringRedisTemplate redis,
                          ObjectMapper objectMapper,
                          ExtractProperties props) {
        this.pageFetcher = pageFetcher;
        this.extractorClient = extractorClient;
        this.sourceTierResolver = sourceTierResolver;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.cacheTtl = props.cacheTtl();
    }

    public ExtractResponse extract(ExtractRequest request) {
        List<Document> documents = new ArrayList<>();
        for (String url : request.urls()) {
            documents.add(extractOne(url));
        }
        return new ExtractResponse(documents);
    }

    private Document extractOne(String url) {
        String key = ExtractCacheKey.of(url);

        String cachedJson = redis.opsForValue().get(key);
        if (cachedJson != null) {
            return objectMapper.readValue(cachedJson, Document.class);
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
}
