package com.comeback.researchplatform.retrievalservice.quota;

import com.comeback.researchplatform.common.GuardrailMode;
import com.comeback.researchplatform.common.GuardrailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;

/**
 * The daily search-credit gauge, and now (per CLAUDE.md's guardrail #2,
 * "now enforced, not just reported") the actual cutoff: {@link #checkBudget()}
 * is called before a cache-miss spend, not just after. Reads the limit from
 * the shared {@code guardrails:} tree, not a standalone {@code quota:}
 * property block.
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final StringRedisTemplate redis;
    private final int dailyLimit;
    private final GuardrailMode mode;

    public QuotaService(StringRedisTemplate redis, GuardrailProperties guardrails) {
        this.redis = redis;
        this.dailyLimit = guardrails.retrieval().quotaDailyLimit();
        this.mode = guardrails.mode();
    }

    public void recordSpend() {
        String key = key();
        redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofHours(24));
    }

    public int remaining() {
        String used = redis.opsForValue().get(key());
        int spent = (used == null) ? 0 : Integer.parseInt(used);
        return Math.max(0, dailyLimit - spent);
    }

    /**
     * Call before a cache-miss spend. {@code ENFORCE} throws and the caller
     * never places the real request; {@code SHADOW} logs the breach and lets
     * it through -- the standard way to introduce a limit without it
     * blocking something legitimate on day one.
     */
    public void checkBudget() {
        if (remaining() > 0) {
            return;
        }
        if (mode == GuardrailMode.ENFORCE) {
            throw new QuotaExceededException(dailyLimit);
        }
        log.warn("Daily search quota ({}) exceeded -- SHADOW mode, proceeding anyway", dailyLimit);
    }

    private String key() {
        return "quota:v1:" + LocalDate.now();
    }
}
