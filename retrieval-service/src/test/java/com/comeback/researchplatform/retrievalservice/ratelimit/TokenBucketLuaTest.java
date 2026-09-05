package com.comeback.researchplatform.retrievalservice.ratelimit;

import com.comeback.researchplatform.retrievalservice.config.RateLimitProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The bucket arithmetic lives in {@code token_bucket.lua} and runs inside Redis, so it
 * cannot be reached with a mock — mocking {@code redis.execute} only proves we called
 * Redis, never that the script decided correctly. These tests run the real script against
 * a real Redis, and they are what replaces the old in-memory {@code TokenBucketTest}:
 * every decision that class pinned is pinned again here, on the Lua side.
 * <p>
 * <b>Needs Redis on localhost:6379</b> — i.e. {@code docker compose up -d redis}. Without
 * it the whole class is skipped rather than failed, so a Docker-less machine can still run
 * {@code mvn test}. That is a deliberate trade: it means a green build does NOT prove the
 * script was exercised. Check for "Tests run: 9" from this class, not just BUILD SUCCESS.
 * Testcontainers would close that hole by starting Redis itself, at the cost of a new
 * dependency and a hard Docker requirement.
 * <p>
 * Elapsed time is faked the way the deleted {@code FakeClock} did, only server-side: the
 * seed script rewrites the stored timestamp relative to <i>Redis's own</i> clock, which is
 * the same clock the bucket reads. Eight simulated idle hours still take microseconds.
 */
class TokenBucketLuaTest {

    private static final int CAPACITY = 3;
    private static final double REFILL_PER_SEC = 1.0;

    /** Mirrors DomainRateLimiter's own prefix — the key it will build for a given host. */
    private static final String KEY_PREFIX = "ratelimit:v1:";

    /**
     * Writes bucket state at an offset from Redis's clock. A negative offset is "this
     * bucket was last touched N ms ago", which is how we simulate idle time.
     */
    private static final RedisScript<Long> SEED = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + tonumber(t[2]) / 1000
            redis.call('HSET', KEYS[1], 'tokens', tonumber(ARGV[1]), 'ts', now + tonumber(ARGV[2]))
            return 1
            """, Long.class);

    /** Moves only the timestamp, leaving the token count as the script last wrote it. */
    private static final RedisScript<Long> SHIFT_CLOCK = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + tonumber(t[2]) / 1000
            redis.call('HSET', KEYS[1], 'ts', now + tonumber(ARGV[1]))
            return 1
            """, Long.class);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static DomainRateLimiter limiter;

    @BeforeAll
    static void connectToRedis() {
        boolean up = false;
        try {
            factory = new LettuceConnectionFactory("localhost", 6379);
            factory.afterPropertiesSet();
            factory.start();
            redis = new StringRedisTemplate(factory);
            redis.hasKey("ping");   // forces a real round trip
            up = true;
        } catch (RuntimeException ignored) {
            // Redis isn't there; the assumption below turns that into a skip, not a failure.
        }
        assumeTrue(up, "Redis not reachable on localhost:6379 — run `docker compose up -d redis`");
        limiter = new DomainRateLimiter(new RateLimitProperties(CAPACITY, REFILL_PER_SEC), redis);
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    /** A host nobody else is using, so a real running system's buckets can't skew a test. */
    private String freshHost() {
        return "bucket-" + UUID.randomUUID() + ".example";
    }

    private void seed(String host, double tokens, long offsetMillis) {
        redis.execute(SEED, List.of(KEY_PREFIX + host),
                String.valueOf(tokens), String.valueOf(offsetMillis));
    }

    private void shiftClock(String host, long offsetMillis) {
        redis.execute(SHIFT_CLOCK, List.of(KEY_PREFIX + host), String.valueOf(offsetMillis));
    }

    @Test
    void aDomainNobodyHasTouchedStartsFull() {
        String url = "https://" + freshHost() + "/article";

        // Nobody has been rate-limited by this domain yet, so it owes no wait: burst first.
        assertThat(limiter.tryAcquire(url)).isTrue();
        assertThat(limiter.tryAcquire(url)).isTrue();
        assertThat(limiter.tryAcquire(url)).isTrue();
        assertThat(limiter.tryAcquire(url)).as("4th call, capacity is 3").isFalse();
    }

    @Test
    void refillsOneTokenPerSecond() {
        String host = freshHost();
        seed(host, 0, -2000);   // drained, last touched 2 seconds ago

        assertThat(limiter.tryAcquire("https://" + host + "/a")).isTrue();
        assertThat(limiter.tryAcquire("https://" + host + "/b")).isTrue();
        assertThat(limiter.tryAcquire("https://" + host + "/c"))
                .as("2 seconds buys exactly 2 tokens at 1/sec").isFalse();
    }

    @Test
    void subSecondGapsStillAccumulate() {
        String host = freshHost();
        seed(host, 0, -600);

        // Two 600ms gaps in a row. Lua has no integer division, so the /1000.0 that
        // mattered in the Java original cannot silently become /1000 here — but anything
        // that rounds elapsed time down to whole seconds (a math.floor, a switch to
        // Redis's second-resolution clock) reaches the same bug by another route: each gap
        // floors to zero tokens, and a caller polling below one second per call never
        // refills at all. Correct behaviour banks 0.6 and then 1.2.
        assertThat(limiter.tryAcquire("https://" + host + "/a"))
                .as("0.6 of a token is banked, but not yet spendable").isFalse();

        shiftClock(host, -600);
        assertThat(limiter.tryAcquire("https://" + host + "/b"))
                .as("the two sub-second gaps sum past 1.0").isTrue();
    }

    @Test
    void anIdleDomainCannotBankTokensOvernight() {
        String host = freshHost();
        seed(host, 0, -28_800_000L);   // 8 hours

        // Without math.min(capacity, ...) this would be 28,800 tokens and the first
        // researcher back on the site would hammer it flat.
        assertThat(limiter.tryAcquire("https://" + host + "/a")).isTrue();
        assertThat(limiter.tryAcquire("https://" + host + "/b")).isTrue();
        assertThat(limiter.tryAcquire("https://" + host + "/c")).isTrue();
        assertThat(limiter.tryAcquire("https://" + host + "/d"))
                .as("8 idle hours still cap at capacity").isFalse();
    }

    @Test
    void aFractionOfATokenIsNotEnough() {
        String host = freshHost();
        seed(host, 0.5, 0);

        // "> 0" instead of ">= 1.0" would let this through and hand out a request we
        // haven't paid for yet.
        assertThat(limiter.tryAcquire("https://" + host + "/a")).isFalse();
    }

    @Test
    void aBackwardsClockDoesNotFreezeTheBucket() {
        String host = freshHost();
        seed(host, 0, 60_000);   // timestamp 60s in the future, as an NTP correction leaves it

        assertThat(limiter.tryAcquire("https://" + host + "/a"))
                .as("no tokens, and a negative elapsed is clamped to 0 rather than draining")
                .isFalse();

        // Only the clock moves here — re-seeding the token count as well would paper over
        // the exact damage this test exists to catch. Without the clamp the call above
        // charged the bucket 60 negative seconds, leaving it near -60 tokens, and 2 seconds
        // of refill cannot dig that out; with it, the bucket sat at 0 and 2 seconds buys 2.
        shiftClock(host, -2000);
        assertThat(limiter.tryAcquire("https://" + host + "/b"))
                .as("bucket recovers as soon as time passes, not 60s later").isTrue();
    }

    @Test
    void aFractionalRefillRateIsHonoured() {
        // 0.5/sec — one call every two seconds, for a site too fragile for 1 rps. This is
        // why RateLimitProperties.refillRate is a double: as an int it truncates to 0 and
        // the bucket never refills at all.
        DomainRateLimiter slow =
                new DomainRateLimiter(new RateLimitProperties(CAPACITY, 0.5), redis);
        String host = freshHost();
        seed(host, 0, -2000);

        assertThat(slow.tryAcquire("https://" + host + "/a"))
                .as("2 seconds at 0.5/sec is exactly 1 token").isTrue();
        assertThat(slow.tryAcquire("https://" + host + "/b")).isFalse();
    }

    @Test
    void theKeyExpiresOnceItWouldHaveRefilledToFull() {
        String host = freshHost();
        limiter.tryAcquire("https://" + host + "/a");

        // capacity/rate seconds after the last touch, an untouched bucket is full again —
        // which is exactly what a missing key gives us. Past that the key is dead weight.
        assertThat(redis.getExpire(KEY_PREFIX + host))
                .isBetween(1L, (long) Math.ceil(CAPACITY / REFILL_PER_SEC));
    }

    @Test
    void concurrentCallersCannotExceedCapacity() throws Exception {
        String url = "https://" + freshHost() + "/article";
        int callers = 20;

        // The reason this is a Lua script and not GET-check-SET: with the read and the
        // decrement in separate round trips, callers interleave between them and all pass
        // a check against the same stale token count. Redis runs a script start to finish
        // with nothing else interleaved, so the whole read-check-write is one step.
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);

        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (limiter.tryAcquire(url)) {
                            allowed.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).as("all callers finished").isTrue();
        }

        // Not "<= capacity": the run is fast enough that refill cannot add a whole token,
        // so the number is exactly the burst. A looser assertion would pass on a broken
        // script that let 4 through.
        assertThat(allowed.get()).isEqualTo(CAPACITY);
    }
}
