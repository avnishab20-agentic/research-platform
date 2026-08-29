package com.comeback.researchplatform.retrievalservice.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketTest {

    /**
     * A clock the test drives by hand. This is the whole payoff for TokenBucket taking a
     * LongSupplier instead of calling System.currentTimeMillis() itself: nine seconds of
     * refill is one instant line here, not a nine-second sleep.
     */
    private static final class FakeClock {
        private long millis = 1_000_000L;

        long now() {
            return millis;
        }

        void advanceMillis(long delta) {
            millis += delta;
        }

        void advanceSeconds(double seconds) {
            advanceMillis((long) (seconds * 1000));
        }
    }

    private static TokenBucket bucket(FakeClock clock) {
        return new TokenBucket(3, 1, clock::now);
    }

    @Test
    void startsFullSoABurstOfCapacityGoesThrough() {
        TokenBucket b = bucket(new FakeClock());

        assertTrue(b.tryAcquire());
        assertTrue(b.tryAcquire());
        assertTrue(b.tryAcquire());
    }

    @Test
    void refusesOnceTheBucketIsDrained() {
        FakeClock clock = new FakeClock();
        TokenBucket b = bucket(clock);

        b.tryAcquire();
        b.tryAcquire();
        b.tryAcquire();

        assertFalse(b.tryAcquire(), "no time has passed, so nothing has refilled");
    }

    @Test
    void refillsOneTokenPerSecond() {
        FakeClock clock = new FakeClock();
        TokenBucket b = bucket(clock);
        drain(b);

        clock.advanceSeconds(1);

        assertTrue(b.tryAcquire());
        assertFalse(b.tryAcquire(), "one second buys exactly one token, not two");
    }

    @Test
    void partialTokenIsNotEnoughToSpend() {
        FakeClock clock = new FakeClock();
        TokenBucket b = bucket(clock);
        drain(b);

        clock.advanceMillis(500);

        assertFalse(b.tryAcquire(), "0.5 of a token cannot pay for a whole request");
    }

    @Test
    void subSecondGapsAccumulateInsteadOfBeingLost() {
        FakeClock clock = new FakeClock();
        TokenBucket b = bucket(clock);
        drain(b);

        // Two half-seconds, each with a tryAcquire in between so refill() runs on the
        // fractional gap. Integer division in refill() would floor both to zero tokens
        // and the bucket would never recover under steady sub-second traffic.
        clock.advanceMillis(500);
        assertFalse(b.tryAcquire());
        clock.advanceMillis(500);

        assertTrue(b.tryAcquire());
    }

    @Test
    void idleTimeDoesNotBankTokensBeyondCapacity() {
        FakeClock clock = new FakeClock();
        TokenBucket b = bucket(clock);
        drain(b);

        clock.advanceSeconds(8 * 60 * 60); // eight untouched hours

        assertTrue(b.tryAcquire());
        assertTrue(b.tryAcquire());
        assertTrue(b.tryAcquire());
        assertFalse(b.tryAcquire(), "capacity is the ceiling; idle time cannot bank 28,800 tokens");
    }

    @Test
    void clockJumpingBackwardsDoesNotFreezeTheBucket() {
        FakeClock clock = new FakeClock();
        TokenBucket b = bucket(clock);
        drain(b);

        // An NTP correction drags the clock back a minute.
        clock.advanceMillis(-60_000);
        assertFalse(b.tryAcquire(), "backwards time earns nothing, but must not corrupt state");

        // The marker resynced on that call, so normal refill resumes immediately rather
        // than stalling until real time caught back up to the old timestamp.
        clock.advanceSeconds(1);
        assertTrue(b.tryAcquire());
    }

    private static void drain(TokenBucket b) {
        while (b.tryAcquire()) {
            // spend everything the bucket currently holds
        }
    }
}
