package com.comeback.researchplatform.retrievalservice.ratelimit;

import com.comeback.researchplatform.retrievalservice.config.RateLimitProperties;
import com.comeback.researchplatform.retrievalservice.url.UrlNormalizer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DomainRateLimiter {

    private static final String KEY_PREFIX = "ratelimit:v1:";

    // Loaded once at startup. Spring Data ships the script to Redis and calls it by SHA
    // from then on, falling back to the full body if Redis has forgotten it.
    private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            readScript(), Long.class);

    private final RateLimitProperties rateLimit;
    private final StringRedisTemplate redis;

    public DomainRateLimiter(RateLimitProperties rateLimit, StringRedisTemplate redis) {
        this.rateLimit = rateLimit;
        this.redis = redis;
    }

    public boolean tryAcquire(String url) {
        String host = UrlNormalizer.host(url);

        // No host means there is nothing to rate-limit against. Refuse rather than throw:
        // PageFetcher is built so one bad URL can't sink a batch.
        if (host == null) {
            return false;
        }

        Long allowed = redis.execute(
                SCRIPT,
                List.of(KEY_PREFIX + host),
                String.valueOf(rateLimit.capacity()),
                String.valueOf(rateLimit.refillRate()));

        return allowed != null && allowed == 1L;
    }

    private static String readScript() {
        try {
            return new ClassPathResource("scripts/token_bucket.lua")
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            // The script is packaged in our own jar. If it's missing the build is broken,
            // and starting up to fail one request at a time would only hide that.
            throw new IllegalStateException("token_bucket.lua missing from classpath", e);
        }
    }
}
