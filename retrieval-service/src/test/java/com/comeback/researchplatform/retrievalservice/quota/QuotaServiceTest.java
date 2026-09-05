package com.comeback.researchplatform.retrievalservice.quota;

import com.comeback.researchplatform.retrievalservice.config.QuotaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis is mocked rather than run: every branch here is arithmetic and key-shaping, and
 * none of it depends on Redis actually behaving like Redis. The real INCR/EXPIRE round
 * trip belongs in the Testcontainers integration test, not here.
 */
class QuotaServiceTest {

    private static final int DAILY_LIMIT = 1000;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private QuotaService quotaService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        quotaService = new QuotaService(redis, new QuotaProperties(DAILY_LIMIT));
    }

    @Test
    void reportsTheFullLimitBeforeAnythingIsSpent() {
        // No key in Redis yet — the day's counter has never been touched.
        when(valueOps.get(anyString())).thenReturn(null);

        assertThat(quotaService.remaining()).isEqualTo(DAILY_LIMIT);
    }

    @Test
    void subtractsWhatHasBeenSpent() {
        when(valueOps.get(anyString())).thenReturn("40");

        assertThat(quotaService.remaining()).isEqualTo(960);
    }

    @Test
    void neverReportsANegativeBalance() {
        // Quota is a gauge, not a cutoff — nothing currently checks it before spending,
        // so overspending is reachable. It must read as 0, not as a negative number.
        when(valueOps.get(anyString())).thenReturn("1500");

        assertThat(quotaService.remaining()).isZero();
    }

    @Test
    void recordingASpendIncrementsAndSetsADailyExpiry() {
        quotaService.recordSpend();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOps).increment(key.capture());
        verify(redis).expire(any(String.class), any(Duration.class));

        assertThat(key.getValue()).isEqualTo("quota:v1:" + LocalDate.now());
    }

    @Test
    void theKeyIsScopedToTodaySoTheCounterResetsDaily() {
        when(valueOps.get(anyString())).thenReturn("10");
        quotaService.remaining();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOps).get(key.capture());

        // Versioned prefix plus the date, matching the search key style. The version is
        // there so a format change is a prefix bump rather than a cache-wide flush.
        assertThat(key.getValue()).startsWith("quota:v1:").endsWith(LocalDate.now().toString());
    }
}
