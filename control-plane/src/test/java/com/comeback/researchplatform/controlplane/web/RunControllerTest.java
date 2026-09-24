package com.comeback.researchplatform.controlplane.web;

import com.comeback.researchplatform.controlplane.planner.PlannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The REST endpoints and one tick of the SSE poller, with Postgres mocked.
 * The poller is run by hand (poll.run()) instead of on its timer, so each
 * test controls exactly what the database returns on each tick.
 */
class RunControllerTest {

    private final UUID runId = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private PlannerService planner;
    private RunController controller;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        planner = mock(PlannerService.class);
        controller = new RunController(planner, jdbc, new ObjectMapper());
    }

    private void runIs(String status) {
        when(jdbc.queryForObject(startsWith("SELECT status FROM runs"), eq(String.class), eq(runId)))
                .thenReturn(status);
        when(jdbc.queryForList(startsWith("SELECT completed, expected"), eq(runId), eq(0)))
                .thenReturn(List.of(Map.of("completed", 2, "expected", 3)));
    }

    private void newActivity(ActivityView... lines) {
        when(jdbc.query(startsWith("SELECT id, node_id, agent, message"), any(RowMapper.class), eq(runId), anyLong()))
                .thenReturn(List.of(lines));
    }

    @Test
    void submitHandsTheQuestionToThePlanner() {
        when(planner.submit("What is the repo rate?")).thenReturn(runId);

        assertThat(controller.submit(new SubmitRunRequest("What is the repo rate?")).runId()).isEqualTo(runId);
    }

    @Test
    void statusOfAnUnknownRunIs404() {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(runId)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> controller.status(runId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void reportCombinesTheRunItsClaimsAndItsConclusion() {
        RunStatusResponse run = new RunStatusResponse(runId, "What is the repo rate?", "VERIFIED");
        ClaimView claim = new ClaimView(UUID.randomUUID(), "The repo rate is 6.5%.", "SUPPORTED",
                "https://rbi.org.in", 1, "6.5 per cent", "Rates", null, null);
        when(jdbc.queryForObject(startsWith("SELECT id, question, status"), any(RowMapper.class), eq(runId)))
                .thenReturn(run);
        when(jdbc.query(startsWith("SELECT c.id"), any(RowMapper.class), eq(runId))).thenReturn(List.of(claim));
        when(jdbc.queryForObject(startsWith("SELECT conclusion"), eq(String.class), eq(runId)))
                .thenReturn("{\"answer\": \"6.5%\"}");

        RunReportResponse report = controller.report(runId);

        assertThat(report.status()).isEqualTo("VERIFIED");
        assertThat(report.claims()).containsExactly(claim);
        assertThat(report.conclusion().get("answer").asText()).isEqualTo("6.5%");
    }

    @Test
    void aReportWithNoConclusionYetHasANullConclusion() {
        when(jdbc.queryForObject(startsWith("SELECT id, question, status"), any(RowMapper.class), eq(runId)))
                .thenReturn(new RunStatusResponse(runId, "q", "RUNNING"));
        when(jdbc.query(startsWith("SELECT c.id"), any(RowMapper.class), eq(runId))).thenReturn(List.of());

        assertThat(controller.report(runId).conclusion()).isNull();
    }

    @Test
    void aTickSendsNewActivityAndProgressButOnlyChangedProgress() throws Exception {
        runIs("RUNNING");
        newActivity(new ActivityView(7, null, "PLANNER", "Split the question into 3 parts"));
        SseEmitter emitter = mock(SseEmitter.class);
        RunController.ProgressPoll poll = controller.new ProgressPoll(runId, emitter);

        poll.run();
        poll.run();

        // Tick 1: one activity line + progress. Tick 2: the same activity line again
        // (this mock doesn't filter by id) but the unchanged progress is not resent.
        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).complete();
    }

    @Test
    void aFinishedRunClosesTheStream() {
        runIs("VERIFIED");
        newActivity();
        SseEmitter emitter = mock(SseEmitter.class);

        controller.new ProgressPoll(runId, emitter).run();

        verify(emitter).complete();
    }

    @Test
    void aRunWithNoFanInRowYetReportsZeroOfZero() throws Exception {
        when(jdbc.queryForObject(startsWith("SELECT status FROM runs"), eq(String.class), eq(runId)))
                .thenReturn("PARTIAL");
        when(jdbc.queryForList(startsWith("SELECT completed, expected"), eq(runId), eq(0))).thenReturn(List.of());
        newActivity();
        SseEmitter emitter = mock(SseEmitter.class);

        controller.new ProgressPoll(runId, emitter).run();

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test
    void aDatabaseErrorEndsTheStreamWithThatError() {
        RuntimeException failure = new RuntimeException("db down");
        when(jdbc.query(anyString(), any(RowMapper.class), eq(runId), anyLong())).thenThrow(failure);
        SseEmitter emitter = mock(SseEmitter.class);

        controller.new ProgressPoll(runId, emitter).run();

        verify(emitter).completeWithError(failure);
    }

    @Test
    void aDeletedRunEndsTheStream() {
        newActivity();
        EmptyResultDataAccessException gone = new EmptyResultDataAccessException(1);
        when(jdbc.queryForObject(startsWith("SELECT status FROM runs"), eq(String.class), eq(runId))).thenThrow(gone);
        SseEmitter emitter = mock(SseEmitter.class);

        controller.new ProgressPoll(runId, emitter).run();

        verify(emitter).completeWithError(gone);
    }
}
