package com.comeback.researchplatform.retrievalservice.ratelimit;

import com.comeback.researchplatform.retrievalservice.config.RateLimitProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lives in the same package as the class under test, which is what lets it reach the
 * package-private constructor and hand in a clock it controls.
 */
class DomainRateLimiterTest {

    private static final class FakeClock {
        private long millis = 1_000_000L;

        long now() {
            return millis;
        }

        void advanceSeconds(long seconds) {
            millis += seconds * 1000;
        }
    }

    private static DomainRateLimiter limiter(FakeClock clock) {
        return new DomainRateLimiter(new RateLimitProperties(3, 1.0), clock::now);
    }

    @Test
    void allowsABurstUpToCapacityThenRefuses() {
        DomainRateLimiter limiter = limiter(new FakeClock());

        assertTrue(limiter.tryAcquire("https://thehindu.com/a"));
        assertTrue(limiter.tryAcquire("https://thehindu.com/b"));
        assertTrue(limiter.tryAcquire("https://thehindu.com/c"));
        assertFalse(limiter.tryAcquire("https://thehindu.com/d"));
    }

    @Test
    void everyUrlOnOneDomainDrawsFromTheSameBucket() {
        DomainRateLimiter limiter = limiter(new FakeClock());

        // Different paths, different subdomain spelling, different scheme case — all one
        // server, so all one bucket. If the key were the whole URL instead of the host,
        // each of these would mint a fresh full bucket and nothing would ever be limited.
        assertTrue(limiter.tryAcquire("https://thehindu.com/news/rbi"));
        assertTrue(limiter.tryAcquire("https://www.thehindu.com/news/budget"));
        assertTrue(limiter.tryAcquire("HTTPS://TheHindu.com/news/election?utm_source=x"));

        assertFalse(limiter.tryAcquire("https://thehindu.com/news/anything-else"));
    }

    @Test
    void separateDomainsDoNotShareAnAllowance() {
        DomainRateLimiter limiter = limiter(new FakeClock());

        limiter.tryAcquire("https://thehindu.com/a");
        limiter.tryAcquire("https://thehindu.com/b");
        limiter.tryAcquire("https://thehindu.com/c");
        assertFalse(limiter.tryAcquire("https://thehindu.com/d"));

        // ndtv.com has never been touched, so its bucket is untouched too.
        assertTrue(limiter.tryAcquire("https://ndtv.com/a"));
    }

    @Test
    void refillsPerDomainAsTimePasses() {
        FakeClock clock = new FakeClock();
        DomainRateLimiter limiter = limiter(clock);

        limiter.tryAcquire("https://thehindu.com/a");
        limiter.tryAcquire("https://thehindu.com/b");
        limiter.tryAcquire("https://thehindu.com/c");
        assertFalse(limiter.tryAcquire("https://thehindu.com/d"));

        clock.advanceSeconds(2);

        assertTrue(limiter.tryAcquire("https://thehindu.com/d"));
        assertTrue(limiter.tryAcquire("https://thehindu.com/e"));
        assertFalse(limiter.tryAcquire("https://thehindu.com/f"), "two seconds buys two tokens");
    }

    @Test
    void aUrlWithNoHostIsRefusedRatherThanThrowing() {
        DomainRateLimiter limiter = limiter(new FakeClock());

        // A perfectly legal URI with nothing where a hostname would go. ConcurrentHashMap
        // would throw on a null key, so this has to be caught before the map is touched.
        assertFalse(limiter.tryAcquire("mailto:someone@example.com"));
        assertFalse(limiter.tryAcquire("about:blank"));
    }
}
