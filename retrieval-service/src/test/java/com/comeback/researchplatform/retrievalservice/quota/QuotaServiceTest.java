package com.comeback.researchplatform.retrievalservice.quota;

import com.comeback.researchplatform.common.GuardrailMode;
import com.comeback.researchplatform.common.GuardrailProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    private QuotaService serviceWith(GuardrailMode mode) {
        var retrieval = new GuardrailProperties.Retrieval(
                DAILY_LIMIT, 1.0, 3, 4, 5, Duration.ofSeconds(5), Duration.ofSeconds(15), 2_097_152, true,
                List.of("http", "https"));
        var run = new GuardrailProperties.Run(
                Duration.ofMinutes(10), 40, 60, 8, 2, "PARTIAL");
        var agent = new GuardrailProperties.Agent(
                new GuardrailProperties.Agent.Researcher(Duration.ofSeconds(90), 25000, 5, "PARTIAL_LOW_CONFIDENCE"));
        var eval = new GuardrailProperties.Eval(0.85, 0.10);
        return new QuotaService(redis, new GuardrailProperties(mode, run, agent, retrieval, eval));
    }

    @Test
    void reportsTheFullLimitBeforeAnythingIsSpent() {
        // No key in Redis yet — the day's counter has never been touched.
        when(valueOps.get(anyString())).thenReturn(null);

        assertThat(serviceWith(GuardrailMode.ENFORCE).remaining()).isEqualTo(DAILY_LIMIT);
    }

    @Test
    void subtractsWhatHasBeenSpent() {
        when(valueOps.get(anyString())).thenReturn("40");

        assertThat(serviceWith(GuardrailMode.ENFORCE).remaining()).isEqualTo(960);
    }

    @Test
    void neverReportsANegativeBalance() {
        when(valueOps.get(anyString())).thenReturn("1500");

        assertThat(serviceWith(GuardrailMode.ENFORCE).remaining()).isZero();
    }

    @Test
    void recordingASpendIncrementsAndSetsADailyExpiry() {
        serviceWith(GuardrailMode.ENFORCE).recordSpend();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOps).increment(key.capture());
        verify(redis).expire(any(String.class), any(Duration.class));

        assertThat(key.getValue()).isEqualTo("quota:v1:" + LocalDate.now());
    }

    @Test
    void theKeyIsScopedToTodaySoTheCounterResetsDaily() {
        when(valueOps.get(anyString())).thenReturn("10");
        QuotaService quotaService = serviceWith(GuardrailMode.ENFORCE);
        quotaService.remaining();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOps).get(key.capture());

        // Versioned prefix plus the date, matching the search key style. The version is
        // there so a format change is a prefix bump rather than a cache-wide flush.
        assertThat(key.getValue()).startsWith("quota:v1:").endsWith(LocalDate.now().toString());
    }

    @Test
    void checkBudgetPassesSilentlyWhenQuotaRemains() {
        when(valueOps.get(anyString())).thenReturn("40");

        serviceWith(GuardrailMode.ENFORCE).checkBudget(); // does not throw
    }

    @Test
    void checkBudgetThrowsInEnforceModeWhenQuotaIsExhausted() {
        when(valueOps.get(anyString())).thenReturn(String.valueOf(DAILY_LIMIT));

        assertThatThrownBy(() -> serviceWith(GuardrailMode.ENFORCE).checkBudget())
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void checkBudgetLogsAndProceedsInShadowModeWhenQuotaIsExhausted() {
        // Quota is now enforced in ENFORCE mode, but SHADOW is how a limit gets
        // introduced without blocking something legitimate on day one.
        when(valueOps.get(anyString())).thenReturn(String.valueOf(DAILY_LIMIT));

        serviceWith(GuardrailMode.SHADOW).checkBudget(); // does not throw
    }
}
