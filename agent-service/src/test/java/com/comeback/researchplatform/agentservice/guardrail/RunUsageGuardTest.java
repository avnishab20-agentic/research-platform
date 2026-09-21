package com.comeback.researchplatform.agentservice.guardrail;

import com.comeback.researchplatform.common.GuardrailMode;
import com.comeback.researchplatform.common.GuardrailProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunUsageGuardTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final int MAX_SEARCHES = 5;

    private JdbcTemplate jdbc;

    private RunUsageGuard guardWith(GuardrailMode mode) {
        jdbc = mock(JdbcTemplate.class);
        var retrieval = new GuardrailProperties.Retrieval(
                1000, 1.0, 3, 4, 5, Duration.ofSeconds(5), Duration.ofSeconds(15), 2_097_152, true);
        var run = new GuardrailProperties.Run(Duration.ofMinutes(10), MAX_SEARCHES, 60, 8, 2, "PARTIAL");
        var agent = new GuardrailProperties.Agent(
                new GuardrailProperties.Agent.Researcher(Duration.ofSeconds(90), 25000, 5, "PARTIAL_LOW_CONFIDENCE"));
        var eval = new GuardrailProperties.Eval(0.85, 0.10);
        return new RunUsageGuard(jdbc, new GuardrailProperties(mode, run, agent, retrieval, eval));
    }

    @Test
    void allowsASearchUnderTheCeiling() {
        RunUsageGuard guard = guardWith(GuardrailMode.ENFORCE);
        when(jdbc.queryForObject(anyString(), any(Class.class), any(UUID.class))).thenReturn(3);

        assertThat(guard.trySearch(RUN_ID)).isTrue();
    }

    @Test
    void refusesInEnforceModeOnceTheCeilingIsExceeded() {
        RunUsageGuard guard = guardWith(GuardrailMode.ENFORCE);
        when(jdbc.queryForObject(anyString(), any(Class.class), any(UUID.class))).thenReturn(MAX_SEARCHES + 1);

        assertThat(guard.trySearch(RUN_ID)).isFalse();
    }

    @Test
    void proceedsInShadowModeEvenPastTheCeiling() {
        // Same reasoning as QuotaService: SHADOW is how a limit gets
        // introduced without it blocking something legitimate on day one.
        RunUsageGuard guard = guardWith(GuardrailMode.SHADOW);
        when(jdbc.queryForObject(anyString(), any(Class.class), any(UUID.class))).thenReturn(MAX_SEARCHES + 1);

        assertThat(guard.trySearch(RUN_ID)).isTrue();
    }

    @Test
    void exactlyAtTheCeilingIsStillAllowed() {
        // The ceiling itself is the last allowed call, not the first refused one.
        RunUsageGuard guard = guardWith(GuardrailMode.ENFORCE);
        when(jdbc.queryForObject(anyString(), any(Class.class), any(UUID.class))).thenReturn(MAX_SEARCHES);

        assertThat(guard.trySearch(RUN_ID)).isTrue();
    }

    @Test
    void failsOpenWhenNoRunUsageRowExists() {
        // A missing counter (a pre-guardrail run, or a race) shouldn't be
        // the reason a real research call gets refused.
        RunUsageGuard guard = guardWith(GuardrailMode.ENFORCE);
        when(jdbc.queryForObject(anyString(), any(Class.class), any(UUID.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(guard.trySearch(RUN_ID)).isTrue();
    }
}
