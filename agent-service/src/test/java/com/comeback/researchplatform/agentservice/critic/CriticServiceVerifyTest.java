package com.comeback.researchplatform.agentservice.critic;

import com.comeback.researchplatform.agentservice.activity.RunActivityLog;
import com.comeback.researchplatform.agentservice.chat.ScriptedChatModel;
import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import com.comeback.researchplatform.agentservice.guardrail.RunUsageGuard;
import com.comeback.researchplatform.agentservice.rag.PassageStore;
import com.comeback.researchplatform.agentservice.retrieval.RetrievalClient;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractResponse;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractedDocument;
import com.comeback.researchplatform.common.GuardrailMode;
import com.comeback.researchplatform.common.GuardrailProperties;
import com.comeback.researchplatform.common.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs verify() with a {@link ScriptedChatModel} as the grader and mocks for
 * everything else. One run holds one claim of every kind that matters:
 * confirmed, contradicted-then-rewritten, unsupported-then-removed, an
 * INFERENCE (never graded) and one with no evidence at all.
 */
class CriticServiceVerifyTest {

    private static final String RBI = "https://rbi.org.in/repo-rate";

    private final UUID runId = UUID.randomUUID();
    private final ClaimRow confirmed = claim("The repo rate is 6.5%.", "FIGURE");
    private final ClaimRow contradicted = claim("The repo rate is 7%.", "FIGURE");
    private final ClaimRow unsupported = claim("Inflation doubled last year.", "FACT");
    private final ClaimRow inference = claim("Borrowing will get cheaper.", "INFERENCE");
    private final ClaimRow noEvidence = claim("The governor has a cat.", "FACT");
    private final ClaimRow rewritten = new ClaimRow(contradicted.id(), "The repo rate is 6.5 per cent.",
            "FIGURE", UUID.randomUUID(), RBI);

    private JdbcTemplate jdbc;
    private PassageStore passages;
    private ClaimCorrector corrector;
    private ConclusionWriter conclusionWriter;
    private RunUsageGuard guard;

    private static ClaimRow claim(String text, String kind) {
        return new ClaimRow(UUID.randomUUID(), text, kind, UUID.randomUUID(), RBI);
    }

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        passages = mock(PassageStore.class);
        corrector = mock(ClaimCorrector.class);
        conclusionWriter = mock(ConclusionWriter.class);
        guard = mock(RunUsageGuard.class);
        when(guard.tryLlmCall(runId)).thenReturn(true);

        Document passage = new Document("The policy repo rate is 6.5 per cent.", Map.of("sourceUrl", RBI));
        when(passages.retrieveTopK(eq(runId), anyString(), anyInt())).thenReturn(List.of(passage));
        when(passages.retrieveTopK(eq(runId), eq(noEvidence.text()), anyInt())).thenReturn(List.of());

        when(corrector.correct(runId, contradicted, Verdict.CONTRADICTED))
                .thenReturn(new ClaimCorrector.Outcome(ClaimCorrector.Kind.REVISED, rewritten));
        when(corrector.correct(runId, unsupported, Verdict.UNSUPPORTED))
                .thenReturn(new ClaimCorrector.Outcome(ClaimCorrector.Kind.REMOVED, unsupported));
    }

    private void storedClaims(List<ClaimRow> claims) {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(runId))).thenReturn(claims);
    }

    private CriticService service(ScriptedChatModel chat, int maxCriticRounds) {
        RetrievalClient retrieval = mock(RetrievalClient.class);
        when(retrieval.extract(anyList())).thenReturn(new ExtractResponse(List.of(
                new ExtractedDocument(RBI, "The policy repo rate is 6.5 per cent.", 1, "OK"))));
        GuardrailProperties guardrails = new GuardrailProperties(GuardrailMode.ENFORCE,
                new GuardrailProperties.Run(Duration.ofMinutes(10), 50, 50, 8, maxCriticRounds, "PARTIAL"),
                null, null, null);
        return new CriticService(chat.builder(), retrieval, passages, jdbc,
                new CriticProperties(10, 3, 0.15, 6, 2), guard, mock(FixtureIO.class), corrector,
                mock(RunActivityLog.class), guardrails, conclusionWriter);
    }

    private static ScriptedChatModel grader() {
        // Round two only ever sees the rewritten claim, so match on its text first.
        return new ScriptedChatModel()
                .reply("The repo rate is 6.5 per cent.",
                        "[{\"index\": 1, \"verdict\": \"SUPPORTED\", \"evidencePassage\": \"6.5 per cent\"}]")
                .reply("You are a fact-checker", """
                        [{"index": 1, "verdict": "SUPPORTED", "evidencePassage": "6.5 per cent"},
                         {"index": 2, "verdict": "CONTRADICTED", "evidencePassage": "6.5 per cent"},
                         {"index": 3, "verdict": "UNSUPPORTED"}]
                        """);
    }

    @Test
    void failedClaimsAreFixedOrRemovedAndTheRunEndsVerified() {
        storedClaims(List.of(confirmed, contradicted, unsupported, inference, noEvidence));

        service(grader(), 2).verify(runId);

        // Every graded claim gets a verdict row -- including the removed one, so the
        // report can still show it as removed. The INFERENCE claim is never graded.
        verify(jdbc, times(4)).update(startsWith("INSERT INTO claim_verdicts"), any(), any(), any());
        verify(jdbc).update(startsWith("INSERT INTO claim_verdicts"), eq(noEvidence.id()),
                eq("UNREACHABLE"), any());
        verify(jdbc).update(startsWith("INSERT INTO claim_verdicts"), eq(contradicted.id()),
                eq("SUPPORTED"), any());
        // Answer = confirmed + rewritten + noEvidence: 0 of 3 failed, under the 15% threshold.
        verify(jdbc).update(startsWith("UPDATE runs SET status = ?"), eq("VERIFIED"), eq(runId));
        // Only claims that passed feed the conclusion.
        verify(conclusionWriter).write(runId, List.of(confirmed, rewritten));
    }

    @Test
    void withOneCriticRoundFailedClaimsStayAndTheRunIsUnverified() {
        storedClaims(List.of(confirmed, contradicted, unsupported));

        service(grader(), 1).verify(runId);

        verify(corrector, never()).correct(any(), any(), any());
        // 2 of 3 failed, well over the 15% threshold -- published, but flagged.
        verify(jdbc).update(startsWith("UPDATE runs SET status = ?"), eq("UNVERIFIED"), eq(runId));
    }

    @Test
    void aRunWithNoClaimsIsUnverified() {
        storedClaims(List.of());

        service(grader(), 2).verify(runId);

        verify(jdbc).update(startsWith("UPDATE runs SET status = 'UNVERIFIED'"), eq(runId));
        verify(conclusionWriter, never()).write(any(), anyList());
    }

    @Test
    void aGraderReplyThatIsNotJsonLeavesClaimsUnchecked() {
        storedClaims(List.of(confirmed));

        service(new ScriptedChatModel().reply("You are a fact-checker", "no idea"), 2).verify(runId);

        verify(jdbc).update(startsWith("INSERT INTO claim_verdicts"), eq(confirmed.id()), eq("UNREACHABLE"), any());
    }

    @Test
    void noLlmBudgetLeavesClaimsUnchecked() {
        when(guard.tryLlmCall(runId)).thenReturn(false);
        storedClaims(List.of(confirmed));

        service(grader(), 2).verify(runId);

        verify(jdbc).update(startsWith("INSERT INTO claim_verdicts"), eq(confirmed.id()), eq("UNREACHABLE"), any());
    }
}
