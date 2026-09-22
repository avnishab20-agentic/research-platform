package com.comeback.researchplatform.controlplane.web;

import com.comeback.researchplatform.controlplane.planner.PlannerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one plain SSE page's backend -- CLAUDE.md: "Rich UI... one plain SSE
 * page only," and the SSE page itself is Claude's column per the working
 * agreement. Submits a run, polls its Postgres state, and streams progress
 * to the browser until the run reaches a terminal status.
 */
@RestController
@RequestMapping("/api/v1/runs")
// The static console lives on retrieval-service's origin (8081); this API
// is on 8083. Restricted to localhost dev origins, not "*" -- this is a
// single-user local demo, not a public API.
@CrossOrigin(origins = {"http://localhost:8081", "http://127.0.0.1:8081"})
public class RunController {

    private static final Logger log = LoggerFactory.getLogger(RunController.class);

    // The three statuses CriticService/FanInService can leave a run in for
    // good -- everything else (RUNNING, FINDINGS_COMPLETE, CLAIMS_READY) is
    // a step along the way, not a place a run stops.
    private static final Set<String> TERMINAL_STATUSES = Set.of("VERIFIED", "UNVERIFIED", "PARTIAL");
    private static final int LEVEL = 0; // flat fan-out only this month, same as FanInService

    private final PlannerService plannerService;
    private final JdbcTemplate jdbc;

    public RunController(PlannerService plannerService, JdbcTemplate jdbc) {
        this.plannerService = plannerService;
        this.jdbc = jdbc;
    }

    @PostMapping
    public SubmitRunResponse submit(@RequestBody SubmitRunRequest request) {
        return new SubmitRunResponse(plannerService.submit(request.question()));
    }

    @GetMapping("/{id}")
    public RunStatusResponse status(@PathVariable("id") UUID id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, question, status FROM runs WHERE id = ?",
                    (rs, rowNum) -> new RunStatusResponse(
                            UUID.fromString(rs.getString("id")), rs.getString("question"), rs.getString("status")),
                    id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No run with id " + id);
        }
    }

    /**
     * One event per status/fan-in change, polled every second -- not pushed
     * from FanInService/WriterService/CriticService directly, since those
     * live in a different service (agent-service) with no channel back to a
     * specific browser connection here. Postgres is already the one place
     * every writer agrees on, so polling it is the simplest correct way to
     * notice a change, not a shortcut around a harder design.
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable("id") UUID id) {
        // 15 minutes: generous against guardrails.run.maxWallClock (10 min
        // default) so a slow-but-legitimate run isn't cut off by the
        // transport before the guardrail itself would have ended it.
        SseEmitter emitter = new SseEmitter(15 * 60 * 1000L);
        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
        AtomicReference<String> lastPayload = new AtomicReference<>();

        ScheduledFuture<?> future = poller.scheduleAtFixedRate(() -> {
            try {
                RunProgressEvent progress = currentProgress(id);
                String payload = progress.status() + ":" + progress.completed() + "/" + progress.expected();
                if (!payload.equals(lastPayload.get())) {
                    lastPayload.set(payload);
                    emitter.send(SseEmitter.event().name("progress").data(progress));
                }
                if (TERMINAL_STATUSES.contains(progress.status())) {
                    emitter.complete();
                }
            } catch (EmptyResultDataAccessException e) {
                emitter.completeWithError(e);
            } catch (Exception e) {
                log.warn("SSE poll failed for run {}", id, e);
                emitter.completeWithError(e);
            }
        }, 0, 1, TimeUnit.SECONDS);

        // All three completion paths (client disconnect, 15-min timeout, a
        // poll throwing) must stop the poller -- otherwise every finished
        // stream leaks one live scheduled thread forever.
        Runnable stopPolling = () -> { future.cancel(true); poller.shutdown(); };
        emitter.onCompletion(stopPolling);
        emitter.onTimeout(() -> { stopPolling.run(); emitter.complete(); });
        emitter.onError(e -> stopPolling.run());

        return emitter;
    }

    private RunProgressEvent currentProgress(UUID id) {
        String status = jdbc.queryForObject("SELECT status FROM runs WHERE id = ?", String.class, id);
        // A run with zero sub-questions (planner failure) has no dag_levels
        // row at all -- treat that as 0/0 rather than erroring the stream.
        Map<String, Object> level = jdbc.query(
                "SELECT completed, expected FROM dag_levels WHERE run_id = ? AND level = ?",
                rs -> rs.next() ? Map.of("completed", rs.getInt("completed"), "expected", rs.getInt("expected")) : Map.of(),
                id, LEVEL);
        int completed = level.isEmpty() ? 0 : (int) level.get("completed");
        int expected = level.isEmpty() ? 0 : (int) level.get("expected");
        List<WorkerView> workers = jdbc.query(
                "SELECT sub_question, status FROM dag_nodes WHERE run_id = ? AND level = ? ORDER BY created_at",
                (rs, rowNum) -> new WorkerView(rs.getString("sub_question"), rs.getString("status")),
                id, LEVEL);
        return new RunProgressEvent(status, completed, expected, workers);
    }

    /** The finished report -- claims joined to their verdict and source,
     *  shaped to match the static UI's existing Claims panel exactly so no
     *  frontend redesign was needed to switch from the scripted mock. */
    @GetMapping("/{id}/report")
    public RunReportResponse report(@PathVariable("id") UUID id) {
        RunStatusResponse run = status(id);
        List<ClaimView> claims = jdbc.query(
                "SELECT c.text, cv.verdict, s.url AS source_url, s.tier, cv.evidence_passage "
                        + "FROM claims c "
                        + "JOIN sources s ON c.source_id = s.id "
                        + "LEFT JOIN claim_verdicts cv ON cv.claim_id = c.id "
                        + "WHERE c.run_id = ? ORDER BY c.created_at",
                (rs, rowNum) -> new ClaimView(
                        rs.getString("text"),
                        rs.getString("verdict") != null ? rs.getString("verdict") : "UNREACHABLE",
                        rs.getString("source_url"), rs.getInt("tier"), rs.getString("evidence_passage")),
                id);
        return new RunReportResponse(run.id(), run.question(), run.status(), claims);
    }
}
