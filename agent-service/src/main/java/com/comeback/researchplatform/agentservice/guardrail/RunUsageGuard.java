package com.comeback.researchplatform.agentservice.guardrail;

import com.comeback.researchplatform.common.GuardrailMode;
import com.comeback.researchplatform.common.GuardrailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * PLAN's run-level ceiling (guardrail #4): "search/LLM-call/wall-clock
 * counters, breach -> PARTIAL". Same atomic-counter shape as FanInService's
 * fan-in -- {@code UPDATE ... RETURNING} under Postgres row locking, so
 * concurrent researchers on the same run can't race each other into
 * under-counting, and the count survives an agent-service restart mid-run
 * (CLAUDE.md: no ConcurrentHashMap/AtomicInteger for exactly this reason).
 * <p>
 * The counter always increments, even past the limit -- only the *decision*
 * (allow or refuse) depends on the configured max and {@link GuardrailMode}.
 * That keeps the number itself honest for later inspection regardless of
 * mode, matching how {@code QuotaService.remaining()} floors at zero but the
 * underlying spend keeps counting.
 */
@Service
public class RunUsageGuard {

    private static final Logger log = LoggerFactory.getLogger(RunUsageGuard.class);

    private final JdbcTemplate jdbc;
    private final GuardrailProperties guardrails;

    public RunUsageGuard(JdbcTemplate jdbc, GuardrailProperties guardrails) {
        this.jdbc = jdbc;
        this.guardrails = guardrails;
    }

    public boolean trySearch(UUID runId) {
        return tryConsume(runId, "searches", guardrails.run().maxSearches());
    }

    public boolean tryLlmCall(UUID runId) {
        return tryConsume(runId, "llm_calls", guardrails.run().maxLlmCalls());
    }

    private boolean tryConsume(UUID runId, String column, int max) {
        int used;
        try {
            // Column name is one of two fixed literals above, never request
            // input -- safe to interpolate into the SQL text.
            used = jdbc.queryForObject(
                    "UPDATE run_usage SET " + column + " = " + column + " + 1 "
                            + "WHERE run_id = ? RETURNING " + column,
                    Integer.class, runId);
        } catch (EmptyResultDataAccessException e) {
            // No run_usage row -- a run from before this guardrail existed,
            // or a race with the row's own insert. Fail open: a missing
            // counter shouldn't be the reason a real research call is
            // refused.
            log.warn("No run_usage row for run {}; allowing {} uncounted", runId, column);
            return true;
        }

        if (used <= max) {
            return true;
        }
        if (guardrails.mode() == GuardrailMode.ENFORCE) {
            log.warn("Run {} exceeded {} ceiling ({}/{}); refusing further calls",
                    runId, column, used, max);
            return false;
        }
        log.warn("Run {} exceeded {} ceiling ({}/{}) -- SHADOW mode, proceeding anyway",
                runId, column, used, max);
        return true;
    }
}
