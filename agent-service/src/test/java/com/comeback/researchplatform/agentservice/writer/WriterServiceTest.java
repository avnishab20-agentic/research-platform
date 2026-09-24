package com.comeback.researchplatform.agentservice.writer;

import com.comeback.researchplatform.agentservice.activity.RunActivityLog;
import com.comeback.researchplatform.agentservice.chat.ScriptedChatModel;
import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import com.comeback.researchplatform.agentservice.guardrail.RunUsageGuard;
import com.comeback.researchplatform.common.ClaimsReady;
import com.comeback.researchplatform.common.KafkaTopics;
import com.comeback.researchplatform.common.ResearchFinding;
import com.comeback.researchplatform.common.SourceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs write() with a {@link ScriptedChatModel} standing in for the LLM and
 * mocks for Postgres and Kafka, then checks which rows it would insert.
 */
class WriterServiceTest {

    private static final String RBI = "https://rbi.org.in/repo-rate";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID runId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private KafkaTemplate<String, Object> kafka;
    private RunUsageGuard guard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        kafka = mock(KafkaTemplate.class);
        guard = mock(RunUsageGuard.class);
        when(guard.tryLlmCall(runId)).thenReturn(true);
        when(jdbc.queryForObject(startsWith("INSERT INTO sources"), eq(UUID.class), eq(runId), anyString(), anyInt()))
                .thenReturn(sourceId);
    }

    private ResearchFinding finding(double confidence, List<SourceRef> sources) {
        return new ResearchFinding(runId, UUID.randomUUID(), "What is the repo rate?",
                "The repo rate is 6.5%.", sources, confidence, "COMPLETE");
    }

    private void storedFindings(String... json) {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(runId))).thenReturn(List.of(json));
    }

    private WriterService service(ScriptedChatModel chat, int maxClaims) {
        return new WriterService(chat.builder(), jdbc, objectMapper, kafka, guard, mock(FixtureIO.class),
                mock(RunActivityLog.class), new WriterProperties(maxClaims));
    }

    @Test
    void eachExtractedClaimBecomesOneRowAndTheCriticIsTold() {
        storedFindings(objectMapper.writeValueAsString(finding(0.95, List.of(new SourceRef(RBI, 1)))));
        ScriptedChatModel chat = new ScriptedChatModel().reply("Break the answer", """
                [{"text": "The repo rate is 6.5%%.", "kind": "FIGURE", "sourceUrl": "%s"},
                 {"text": "Rates are steady.", "kind": "INFERENCE", "sourceUrl": "%s"}]
                """.formatted(RBI, RBI));

        service(chat, 5).write(runId);

        verify(jdbc, times(2)).update(startsWith("INSERT INTO claims"), eq(runId), any(), anyString(), anyString(),
                eq(sourceId), anyString());
        verify(jdbc).update(startsWith("UPDATE runs SET status = 'CLAIMS_READY'"), eq(runId));
        verify(kafka).send(KafkaTopics.CLAIMS_READY, runId.toString(), new ClaimsReady(runId));
    }

    @Test
    void aClaimCitingAnUnknownUrlFallsBackToARealSource() {
        storedFindings(objectMapper.writeValueAsString(finding(0.95, List.of(new SourceRef(RBI, 1)))));
        ScriptedChatModel chat = new ScriptedChatModel().reply("Break the answer",
                "[{\"text\": \"The repo rate is 6.5%.\", \"kind\": \"FACT\", \"sourceUrl\": \"https://made-up.example\"}]");

        service(chat, 5).write(runId);

        verify(jdbc).update(startsWith("INSERT INTO claims"), eq(runId), any(), anyString(),
                eq("The repo rate is 6.5%."), eq(sourceId), eq("FACT"));
    }

    @Test
    void theModelOvershootingTheClaimCapIsCutBackToTheCap() {
        storedFindings(objectMapper.writeValueAsString(finding(0.95, List.of(new SourceRef(RBI, 1)))));
        ScriptedChatModel chat = new ScriptedChatModel().reply("Break the answer", """
                [{"text": "one", "kind": "FACT", "sourceUrl": "%s"},
                 {"text": "two", "kind": "FACT", "sourceUrl": "%s"},
                 {"text": "three", "kind": "FACT", "sourceUrl": "%s"}]
                """.formatted(RBI, RBI, RBI));

        service(chat, 2).write(runId);

        verify(jdbc, times(2)).update(startsWith("INSERT INTO claims"), eq(runId), any(), anyString(), anyString(),
                eq(sourceId), anyString());
    }

    @Test
    void unansweredFindingsAndUnreadableRowsAreSkipped() {
        storedFindings(
                objectMapper.writeValueAsString(finding(0.0, List.of(new SourceRef(RBI, 1)))),
                objectMapper.writeValueAsString(finding(0.9, List.of())),
                "not json");
        ScriptedChatModel chat = new ScriptedChatModel();

        service(chat, 5).write(runId);

        // Nothing was worth asking the model about, and the run still moves on.
        verify(jdbc, never()).update(startsWith("INSERT INTO claims"), any(Object[].class));
        verify(jdbc).update(startsWith("UPDATE runs SET status = 'CLAIMS_READY'"), eq(runId));
    }

    @Test
    void noLlmBudgetMeansNoClaimsButTheRunStillMovesOn() {
        when(guard.tryLlmCall(runId)).thenReturn(false);
        storedFindings(objectMapper.writeValueAsString(finding(0.95, List.of(new SourceRef(RBI, 1)))));

        service(new ScriptedChatModel(), 5).write(runId);

        verify(jdbc, never()).update(startsWith("INSERT INTO claims"), any(Object[].class));
        verify(kafka).send(KafkaTopics.CLAIMS_READY, runId.toString(), new ClaimsReady(runId));
    }

    @Test
    void aReplyThatIsNotValidJsonGivesNoClaimsInsteadOfFailingTheRun() {
        storedFindings(objectMapper.writeValueAsString(finding(0.95, List.of(new SourceRef(RBI, 1)))));
        ScriptedChatModel chat = new ScriptedChatModel().reply("Break the answer", "sorry, I can't do that");

        service(chat, 5).write(runId);

        verify(jdbc, never()).update(startsWith("INSERT INTO claims"), any(Object[].class));
        verify(jdbc).update(startsWith("UPDATE runs SET status = 'CLAIMS_READY'"), eq(runId));
    }
}
