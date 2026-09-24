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

    // One complete statement per counter. Written out in full rather than built
    // from the column name, so no part of the SQL text is ever assembled at runtime.
    private static final String COUNT_SEARCH =
            "UPDATE run_usage SET searches = searches + 1 WHERE run_id = ? RETURNING searches";
    private static final String COUNT_LLM_CALL =
            "UPDATE run_usage SET llm_calls = llm_calls + 1 WHERE run_id = ? RETURNING llm_calls";

    private final JdbcTemplate jdbc;
    private final GuardrailProperties guardrails;

    public RunUsageGuard(JdbcTemplate jdbc, GuardrailProperties guardrails) {
        this.jdbc = jdbc;
        this.guardrails = guardrails;
    }

    public boolean trySearch(UUID runId) {
        return tryConsume(runId, COUNT_SEARCH, "searches", guardrails.run().maxSearches());
    }

    public boolean tryLlmCall(UUID runId) {
        return tryConsume(runId, COUNT_LLM_CALL, "llm_calls", guardrails.run().maxLlmCalls());
    }

    /** {@code column} is only used in log messages; {@code countSql} does the counting. */
    private boolean tryConsume(UUID runId, String countSql, String column, int max) {
        Integer used;
        try {
            used = jdbc.queryForObject(countSql, Integer.class, runId);
        } catch (EmptyResultDataAccessException e) {
            // No run_usage row -- a run from before this guardrail existed,
            // or a race with the row's own insert. Fail open: a missing
            // counter shouldn't be the reason a real research call is
            // refused.
            log.warn("No run_usage row for run {}; allowing {} uncounted", runId, column);
            return true;
        }

        // null can't really come back from RETURNING on a real row; treat it like
        // the missing-row case above and fail open.
        if (used == null || used <= max) {
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
