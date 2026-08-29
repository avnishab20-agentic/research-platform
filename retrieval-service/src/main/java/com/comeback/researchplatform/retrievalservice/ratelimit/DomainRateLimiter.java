package com.comeback.researchplatform.retrievalservice.ratelimit;



import com.comeback.researchplatform.retrievalservice.url.UrlNormalizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import com.comeback.researchplatform.retrievalservice.config.RateLimitProperties;

@Component
public class DomainRateLimiter {

    private final RateLimitProperties rateLimit;
    private final LongSupplier clockMillis;

    // One bucket per domain, never pruned. A run touches a few hundred domains and a
    // bucket is a few tens of bytes, so the map stays trivially small. Eviction would
    // cost more to maintain than it saves.
    private final Map<String, TokenBucket> tokenBuckets = new ConcurrentHashMap<>();

    // @Autowired is required here, not decorative: with more than one constructor Spring
    // will not guess, and falls back to looking for a no-arg one it won't find.
    @Autowired
    public DomainRateLimiter(RateLimitProperties rateLimit) {
        this(rateLimit, System::currentTimeMillis);
    }

    // Package-private, for tests that need to drive the clock by hand.
    DomainRateLimiter(RateLimitProperties rateLimit, LongSupplier clockMillis) {
        this.rateLimit = rateLimit;
        this.clockMillis = clockMillis;
    }

    public boolean tryAcquire(String url) {

        String host = UrlNormalizer.host(url);

        // No host means there is nothing to rate-limit against, and ConcurrentHashMap
        // rejects a null key outright. Refuse rather than throw: PageFetcher is built so
        // one bad URL can't sink a batch, and throwing here would undo that.
        if (host == null) {
            return false;
        }

        TokenBucket bucket = tokenBuckets.computeIfAbsent(
                host,
                h -> new TokenBucket(rateLimit.capacity(), rateLimit.refillRate(), clockMillis));

        return bucket.tryAcquire();
    }



}
