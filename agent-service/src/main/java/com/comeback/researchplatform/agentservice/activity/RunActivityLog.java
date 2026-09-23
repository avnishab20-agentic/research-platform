package com.comeback.researchplatform.agentservice.activity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Plain-language lines about what an agent just did, for the browser's
 * activity feed (run_events, V4 migration).
 * <p>
 * REQUIRES_NEW because the Writer and Critic run inside one long
 * transaction: without it, every line they write would stay invisible to
 * the SSE poll until the whole step committed, and the feed would arrive
 * all at once at the end instead of live.
 */
@Component
public class RunActivityLog {

    private static final Logger log = LoggerFactory.getLogger(RunActivityLog.class);

    private final JdbcTemplate jdbc;

    public RunActivityLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID runId, UUID nodeId, String agent, String message) {
        // A feed line is decoration around the run, never part of it -- a
        // failed insert must not fail the research step that produced it.
        try {
            jdbc.update("INSERT INTO run_events (run_id, node_id, agent, message) VALUES (?, ?, ?, ?)",
                    runId, nodeId, agent, message);
        } catch (Exception e) {
            log.warn("Could not record activity for run {}: {}", runId, message, e);
        }
    }
}
