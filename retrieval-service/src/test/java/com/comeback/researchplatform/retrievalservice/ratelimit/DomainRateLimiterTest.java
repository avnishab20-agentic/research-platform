package com.comeback.researchplatform.retrievalservice.ratelimit;

import com.comeback.researchplatform.retrievalservice.config.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bucket arithmetic now lives in token_bucket.lua and runs inside Redis, so it cannot
 * be reached from here. What is still Java — and still worth pinning — is which key a URL
 * maps to, and the refusal path for a URL with no host.
 * <p>
 * The refill/burst/idle-cap behaviour these tests used to cover needs a real Redis. See
 * the note in the session log; it is currently unverified.
 */
class DomainRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final DomainRateLimiter limiter =
            new DomainRateLimiter(new RateLimitProperties(3, 1.0, 3), redis);

    @SuppressWarnings("unchecked")
    private List<String> keysPassedToRedis() {
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), any(), any());
        return keys.getValue();
    }

    @Test
    void keysTheBucketOnTheHostNotTheWholeUrl() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);

        limiter.tryAcquire("HTTPS://WWW.TheHindu.com/news/rbi?utm_source=x#top");

        // If the key were the whole URL, every article on a site would mint its own fresh
        // full bucket and the limiter would allow everything, forever.
        assertEquals(List.of("ratelimit:v1:thehindu.com"), keysPassedToRedis());
    }

    @Test
    void passesCapacityAndRefillRateToTheScript() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);

        limiter.tryAcquire("https://thehindu.com/a");

        verify(redis).execute(any(RedisScript.class), anyList(), eq("3"), eq("1.0"));
    }

    @Test
    void returnsWhateverTheScriptDecided() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
        assertTrue(limiter.tryAcquire("https://thehindu.com/a"));

        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        assertFalse(limiter.tryAcquire("https://thehindu.com/b"));
    }

    @Test
    void aUrlWithNoHostIsRefusedWithoutTouchingRedis() {
        // A legal URI with nothing where a hostname would go. There is no domain to limit,
        // so refuse — PageFetcher is built so one bad URL can't sink a batch.
        assertFalse(limiter.tryAcquire("mailto:someone@example.com"));
        assertFalse(limiter.tryAcquire("about:blank"));

        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(), any());
    }
}
