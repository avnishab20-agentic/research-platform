package com.comeback.researchplatform.controlplane.fanin;

import com.comeback.researchplatform.common.KafkaTopics;
import com.comeback.researchplatform.common.ResearchFinding;
import com.comeback.researchplatform.common.RunReady;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The fan-in counter -- CLAUDE.md's "Postgres counter, not Kafka Streams
 * windowing" architecture decision. {@code UPDATE ... RETURNING} is the
 * whole mechanism: Postgres row-level locking serializes concurrent
 * UPDATEs against the same (run_id, level) row, so when several findings
 * for one run arrive at the same instant, each transaction still gets a
 * distinct, correctly-incremented {@code completed} value back -- exactly
 * one of them will ever see {@code completed == expected}. That one
 * triggers the run's completion; every other caller sees a smaller number
 * and does nothing further. No in-memory counter, no
 * ConcurrentHashMap/AtomicInteger (CLAUDE.md: "that breaks
 * restart-survival") -- if agent-service or control-plane restarts
 * mid-run, the count is still sitting in Postgres, untouched.
 */
@Service
public class FanInService {

    private static final Logger log = LoggerFactory.getLogger(FanInService.class);
    private static final int LEVEL = 0; // flat fan-out only this month

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public FanInService(JdbcTemplate jdbc, ObjectMapper objectMapper, KafkaTemplate<String, Object> kafkaTemplate) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    public void recordFinding(ResearchFinding finding) {
        jdbc.update("UPDATE dag_nodes SET status = ?, finding = ?::jsonb, updated_at = now() WHERE id = ?",
                finding.status(), writeJson(finding), finding.nodeId());

        Map<String, Object> row = jdbc.queryForMap(
                "UPDATE dag_levels SET completed = completed + 1 WHERE run_id = ? AND level = ? "
                        + "RETURNING completed, expected",
                finding.runId(), LEVEL);
        int completed = ((Number) row.get("completed")).intValue();
        int expected = ((Number) row.get("expected")).intValue();

        log.info("Run {} finding recorded: {}/{}", finding.runId(), completed, expected);

        if (completed == expected) {
            // Exactly one caller ever reaches here per (run_id, level) --
            // guaranteed by the row lock the UPDATE above held.
            jdbc.update("UPDATE runs SET status = 'FINDINGS_COMPLETE', updated_at = now() WHERE id = ?",
                    finding.runId());
            log.info("Run {} fan-in complete: all {} researchers finished", finding.runId(), expected);
            kafkaTemplate.send(KafkaTopics.RUN_READY, finding.runId().toString(), new RunReady(finding.runId()));
        }
    }

    /**
     * Releases any (run_id, level) whose deadline has passed but hasn't
     * completed -- a hung or dead researcher can't strand a run forever.
     * "Released" means: force completed = expected so the counter can
     * never trigger again for this level, and mark the run PARTIAL rather
     * than silently dropping it (CLAUDE.md: "a failed run is published,
     * never silently dropped").
     */
    @Transactional
    public void releaseExpiredLevels() {
        // The Postgres driver can't infer a SQL type for a raw Instant --
        // needs java.sql.Timestamp explicitly (found by actually running this
        // against real Postgres, not by reading the code).
        List<Map<String, Object>> expired = jdbc.queryForList(
                "SELECT run_id, level FROM dag_levels WHERE completed < expected AND deadline < ?",
                java.sql.Timestamp.from(Instant.now()));
        for (Map<String, Object> row : expired) {
            UUID runId = (UUID) row.get("run_id");
            int level = ((Number) row.get("level")).intValue();
            jdbc.update("UPDATE dag_levels SET completed = expected WHERE run_id = ? AND level = ?",
                    runId, level);
            jdbc.update("UPDATE runs SET status = 'PARTIAL', updated_at = now() WHERE id = ?", runId);
            log.warn("Run {} level {} deadline exceeded; released as PARTIAL", runId, level);
            // Still hand off to the Writer with whatever findings did arrive --
            // CLAUDE.md: "a failed run is published, never silently dropped."
            kafkaTemplate.send(KafkaTopics.RUN_READY, runId.toString(), new RunReady(runId));
        }
    }

    private String writeJson(ResearchFinding finding) {
        try {
            return objectMapper.writeValueAsString(finding);
        } catch (Exception e) {
            // Jackson failing on our own record would be a code bug, not a
            // runtime condition to recover from -- but this listener must
            // never throw and lose the finding, so fall back to a minimal
            // valid JSON payload rather than crashing the consumer.
            log.error("Failed to serialize finding for node {}", finding.nodeId(), e);
            return "{\"error\":\"serialization failed\"}";
        }
    }
}
