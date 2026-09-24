package com.comeback.researchplatform.controlplane.web;

import com.comeback.researchplatform.controlplane.planner.PlannerService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    // Threads shared by every open progress stream. Each tick is a few quick
    // queries, so a handful of threads serves many browsers at once.
    private static final int POLLER_THREADS = 4;

    private final PlannerService plannerService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    // One scheduler for the whole controller, not one per stream: a stream only
    // starts and cancels its own timer, and the threads are closed once, in
    // shutdown(), when the app stops.
    private final ScheduledExecutorService poller = Executors.newScheduledThreadPool(POLLER_THREADS);

    public RunController(PlannerService plannerService, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.plannerService = plannerService;
        this.jdbc = jdbc;
    }

    @PreDestroy
    void shutdown() {
        poller.shutdownNow();
    }

    @PostMapping
    public SubmitRunResponse submit(@RequestBody SubmitRunRequest request) {
        return new SubmitRunResponse(plannerService.submit(request.question()));
    }

    @GetMapping("/{id}")
    public RunStatusResponse status(@PathVariable("id") UUID id) {
        MDC.put("runId", id.toString());
        try {
            return loadStatus(id);
        } finally {
            MDC.remove("runId");
        }
    }

    // Not MDC-wrapped: report() below calls this directly (not the public
    // status() endpoint method) so its own MDC.remove in a finally can't
    // fire partway through report()'s own remaining work.
    private RunStatusResponse loadStatus(UUID id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, question, status FROM runs WHERE id = ?",
                    (rs, rowNum) -> new RunStatusResponse(
                            UUID.fromString(rs.getString("id")), rs.getString("question"), rs.getString("status")),
                    id);
        } catch (EmptyResultDataAccessException e) {
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
        ScheduledFuture<?> ticks = poller.scheduleAtFixedRate(
                new ProgressPoll(id, emitter), 0, 1, TimeUnit.SECONDS);

        // All three ways a stream ends (client disconnect, 15-min timeout, a
        // poll throwing) must cancel its timer -- otherwise every finished
        // stream would keep querying Postgres once a second forever.
        emitter.onCompletion(() -> ticks.cancel(false));
        emitter.onTimeout(() -> {
            ticks.cancel(false);
            emitter.complete();
        });
        emitter.onError(e -> ticks.cancel(false));

        return emitter;
    }

    /**
     * One tick of the SSE stream, run once a second by the shared poller. It
     * remembers what it already sent (the last activity id and the last
     * progress line) so each tick only sends what is new.
     * <p>
     * The scheduler never runs two ticks of the same stream at once, and each
     * tick sees what the previous one wrote, so plain fields are enough.
     * Package-visible so RunControllerTest can run one tick directly.
     */
    class ProgressPoll implements Runnable {

        private final UUID runId;
        private final SseEmitter emitter;
        private long lastActivityId = 0;
        private String lastProgress = null;

        ProgressPoll(UUID runId, SseEmitter emitter) {
            this.runId = runId;
            this.emitter = emitter;
        }

        @Override
        public void run() {
            // This runs on the poller thread, not the request thread that called
            // events() -- MDC is per-thread, so it has to be set here as well.
            MDC.put("runId", runId.toString());
            try {
                sendNewActivity();
                RunProgressEvent progress = sendProgressIfChanged();
                if (TERMINAL_STATUSES.contains(progress.status())) {
                    emitter.complete();
                }
            } catch (EmptyResultDataAccessException e) {
                emitter.completeWithError(e);
            } catch (Exception e) {
                log.warn("SSE poll failed for run {}", runId, e);
                emitter.completeWithError(e);
            } finally {
                MDC.remove("runId");
            }
        }

        // Sent before progress, so the Critic's closing "Done" line reaches the
        // browser before a terminal status closes the stream.
        private void sendNewActivity() throws IOException {
            for (ActivityView activity : activitySince(runId, lastActivityId)) {
                emitter.send(SseEmitter.event().name("activity").data(activity));
                lastActivityId = activity.id();
            }
        }

        private RunProgressEvent sendProgressIfChanged() throws IOException {
            RunProgressEvent progress = currentProgress(runId);
            String summary = progress.status() + ":" + progress.completed() + "/" + progress.expected();
            if (!summary.equals(lastProgress)) {
                lastProgress = summary;
                emitter.send(SseEmitter.event().name("progress").data(progress));
            }
            return progress;
        }
    }

    private RunProgressEvent currentProgress(UUID id) {
        String status = jdbc.queryForObject("SELECT status FROM runs WHERE id = ?", String.class, id);
        // A run with zero sub-questions (planner failure) has no dag_levels
        // row at all -- treat that as 0/0 rather than erroring the stream.
        List<Map<String, Object>> levels = jdbc.queryForList(
                "SELECT completed, expected FROM dag_levels WHERE run_id = ? AND level = ?", id, LEVEL);
        int completed = 0;
        int expected = 0;
        if (!levels.isEmpty()) {
            completed = ((Number) levels.get(0).get("completed")).intValue();
            expected = ((Number) levels.get(0).get("expected")).intValue();
        }
        List<WorkerView> workers = jdbc.query(
                "SELECT id, sub_question, status FROM dag_nodes WHERE run_id = ? AND level = ? ORDER BY created_at",
                (rs, rowNum) -> new WorkerView((UUID) rs.getObject("id"), rs.getString("sub_question"),
                        rs.getString("status")),
                id, LEVEL);
        return new RunProgressEvent(status, completed, expected, workers);
    }

    private List<ActivityView> activitySince(UUID runId, long afterId) {
        return jdbc.query(
                "SELECT id, node_id, agent, message FROM run_events WHERE run_id = ? AND id > ? ORDER BY id",
                (rs, rowNum) -> new ActivityView(rs.getLong("id"), (UUID) rs.getObject("node_id"),
                        rs.getString("agent"), rs.getString("message")),
                runId, afterId);
    }

    /** The finished report -- claims joined to their verdict and source,
     *  shaped to match the static UI's existing Claims panel exactly so no
     *  frontend redesign was needed to switch from the scripted mock. */
    @GetMapping("/{id}/report")
    public RunReportResponse report(@PathVariable("id") UUID id) {
        MDC.put("runId", id.toString());
        try {
            return buildReport(id);
        } finally {
            MDC.remove("runId");
        }
    }

    private RunReportResponse buildReport(UUID id) {
        RunStatusResponse run = loadStatus(id);
        List<ClaimView> claims = jdbc.query(
                "SELECT c.id, c.text, cv.verdict, s.url AS source_url, s.tier, cv.evidence_passage, "
                        + "c.section_heading, c.original_text, c.correction "
                        + "FROM claims c "
                        + "JOIN sources s ON c.source_id = s.id "
                        + "LEFT JOIN claim_verdicts cv ON cv.claim_id = c.id "
                        + "WHERE c.run_id = ? ORDER BY c.created_at",
                (rs, rowNum) -> new ClaimView(
                        (UUID) rs.getObject("id"), rs.getString("text"),
                        rs.getString("verdict") != null ? rs.getString("verdict") : "UNREACHABLE",
                        rs.getString("source_url"), rs.getInt("tier"), rs.getString("evidence_passage"),
                        rs.getString("section_heading"), rs.getString("original_text"), rs.getString("correction")),
                id);
        String conclusion = jdbc.queryForObject("SELECT conclusion::text FROM runs WHERE id = ?", String.class, id);
        return new RunReportResponse(run.id(), run.question(), run.status(),
                conclusion == null ? null : objectMapper.readTree(conclusion), claims);
    }
}
