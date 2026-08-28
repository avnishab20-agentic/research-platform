package com.comeback.researchplatform.retrievalservice.ratelimit;

import java.util.function.LongSupplier;

/**
 * One bucket, for one domain.
 * <p>
 * Holds at most {@code capacity} tokens (the burst) and regains {@code refillPerSecond}
 * of them per second. Every request spends one. No background timer refills it — instead
 * each call works out how much time has passed since the last look and converts that
 * into tokens, which is the same result for none of the cost.
 */
public class TokenBucket {

    private final double capacity;
    private final double refillPerSecond;
    private final LongSupplier clockMillis;

    private double tokens;
    private long lastRefillMillis;

    public TokenBucket(double capacity, double refillPerSecond, LongSupplier clockMillis) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.clockMillis = clockMillis;
        // A domain nobody has touched yet is not owed any wait, so it starts full.
        this.tokens = capacity;
        this.lastRefillMillis = clockMillis.getAsLong();
    }

    /**
     * @return true if a token was available and has been spent; false if the caller
     *         must back off. Never blocks — waiting is the caller's decision.
     */
    public synchronized boolean tryAcquire() {
        refill();
        // Not "> 0": holding 0.3 of a token is not enough to make a whole request.
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = clockMillis.getAsLong();
        long elapsedMillis = now - lastRefillMillis;

        // Move the marker first, unconditionally. If the clock jumped backwards (NTP
        // correction), this resyncs immediately; leaving it stale would make elapsed
        // negative on every future call and freeze the bucket until real time caught up.
        lastRefillMillis = now;

        if (elapsedMillis <= 0) {
            return;
        }

        // Millis to seconds, and 1000.0 not 1000 — integer division would floor every
        // sub-second gap to zero and the bucket would never refill under steady traffic.
        double earned = (elapsedMillis / 1000.0) * refillPerSecond;

        // Math.min is the idle cap: eight untouched hours must not bank 28,800 tokens.
        tokens = Math.min(capacity, tokens + earned);
    }
}
