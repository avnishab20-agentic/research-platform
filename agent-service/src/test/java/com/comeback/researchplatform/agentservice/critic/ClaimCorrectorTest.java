package com.comeback.researchplatform.agentservice.critic;

import com.comeback.researchplatform.agentservice.activity.RunActivityLog;
import com.comeback.researchplatform.agentservice.chat.ScriptedChatModel;
import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import com.comeback.researchplatform.agentservice.guardrail.RunUsageGuard;
import com.comeback.researchplatform.agentservice.rag.PassageStore;
import com.comeback.researchplatform.agentservice.retrieval.RetrievalClient;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractResponse;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractedDocument;
import com.comeback.researchplatform.agentservice.retrieval.dto.SearchResponse;
import com.comeback.researchplatform.agentservice.retrieval.dto.SearchResult;
import com.comeback.researchplatform.common.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * correct() with a {@link ScriptedChatModel} deciding REVISE or DROP and mocks
 * for search, extract, the vector store and Postgres.
 */
class ClaimCorrectorTest {

    private static final String KNOWN = "https://rbi.org.in/repo-rate";
    private static final String NEW_PAGE = "https://thehindu.com/rbi-holds-rate";

    private final UUID runId = UUID.randomUUID();
    private final UUID newSourceId = UUID.randomUUID();
    private final ClaimRow claim = new ClaimRow(UUID.randomUUID(), "The repo rate is 7%.", "FIGURE",
            UUID.randomUUID(), KNOWN);

    private JdbcTemplate jdbc;
    private RetrievalClient retrieval;
    private PassageStore passages;
    private RunUsageGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = mock(JdbcTemplate.class);
        retrieval = mock(RetrievalClient.class);
        passages = mock(PassageStore.class);
        guard = mock(RunUsageGuard.class);
        when(guard.trySearch(runId)).thenReturn(true);
        when(guard.tryLlmCall(runId)).thenReturn(true);

        // The run already knows one source, at tier 1.
        ResultSet row = mock(ResultSet.class);
        when(row.getString("url")).thenReturn(KNOWN);
        when(row.getInt("tier")).thenReturn(1);
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            handler.processRow(row);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), eq(runId));

        when(retrieval.search(anyList(), anyInt(), any(), anyInt())).thenReturn(new SearchResponse(List.of(
                new SearchResult(KNOWN, "already read", "", 1),
                new SearchResult(NEW_PAGE, "new", "", 2),
                new SearchResult(NEW_PAGE, "same page again", "", 2)), 1, 0));
        when(retrieval.extract(anyList())).thenReturn(new ExtractResponse(List.of(
                new ExtractedDocument(NEW_PAGE, "RBI held the repo rate at 6.5 per cent.", 2, "OK"))));
        when(passages.retrieveTopK(eq(runId), anyString(), anyInt())).thenReturn(List.of(
                new Document("RBI held the repo rate at 6.5 per cent.", Map.of("sourceUrl", NEW_PAGE)),
                new Document("A page with no known tier.", Map.of("sourceUrl", "https://unknown.example"))));
        when(jdbc.queryForObject(startsWith("INSERT INTO sources"), eq(UUID.class), eq(runId), eq(NEW_PAGE), eq(2)))
                .thenReturn(newSourceId);
    }

    private ClaimCorrector corrector(ScriptedChatModel chat) {
        return new ClaimCorrector(chat.builder(), retrieval, passages, jdbc,
                new CriticProperties(10, 3, 0.15, 6, 2), guard, mock(RunActivityLog.class), mock(FixtureIO.class));
    }

    private static ScriptedChatModel decides(String json) {
        return new ScriptedChatModel().reply("You repair a statement", json);
    }

    @Test
    void aRewriteBasedOnAPassageItWasShownIsAccepted() {
        ClaimCorrector.Outcome outcome = corrector(decides(
                "{\"action\": \"REVISE\", \"text\": \"The repo rate is 6.5%.\", \"sourceUrl\": \"" + NEW_PAGE + "\"}"))
                .correct(runId, claim, Verdict.CONTRADICTED);

        assertThat(outcome.kind()).isEqualTo(ClaimCorrector.Kind.REVISED);
        assertThat(outcome.claim().text()).isEqualTo("The repo rate is 6.5%.");
        assertThat(outcome.claim().sourceId()).isEqualTo(newSourceId);
        verify(jdbc).update(startsWith("UPDATE claims SET original_text = text"), eq("The repo rate is 6.5%."),
                eq(newSourceId), eq(claim.id()));
    }

    @Test
    void onlyNewPagesAreFetchedAndEachOnlyOnce() {
        corrector(decides("{\"action\": \"DROP\"}")).correct(runId, claim, Verdict.CONTRADICTED);

        verify(retrieval).extract(List.of(NEW_PAGE));
        verify(passages).index(runId, NEW_PAGE, "RBI held the repo rate at 6.5 per cent.");
    }

    @Test
    void aDropDecisionRemovesTheClaim() {
        ClaimCorrector.Outcome outcome = corrector(decides("{\"action\": \"DROP\"}"))
                .correct(runId, claim, Verdict.UNSUPPORTED);

        assertThat(outcome.kind()).isEqualTo(ClaimCorrector.Kind.REMOVED);
        verify(jdbc).update(startsWith("UPDATE claims SET correction = 'REMOVED'"), eq(claim.id()));
    }

    @Test
    void aRewritePointingAtAUrlItWasNeverShownIsRejected() {
        // This is the fabrication the project exists to catch.
        ClaimCorrector.Outcome outcome = corrector(decides(
                "{\"action\": \"REVISE\", \"text\": \"The repo rate is 6.5%.\", \"sourceUrl\": \"https://made-up.example\"}"))
                .correct(runId, claim, Verdict.CONTRADICTED);

        assertThat(outcome.kind()).isEqualTo(ClaimCorrector.Kind.UNCHANGED);
        verify(jdbc, never()).update(startsWith("UPDATE claims"), any(Object[].class));
    }

    @Test
    void noPassagesFromAKnownSourceRemovesTheClaim() {
        when(passages.retrieveTopK(eq(runId), anyString(), anyInt())).thenReturn(List.of(
                new Document("A page with no known tier.", Map.of("sourceUrl", "https://unknown.example"))));
        when(retrieval.extract(anyList())).thenReturn(new ExtractResponse(List.of()));

        ClaimCorrector.Outcome outcome = corrector(decides("{\"action\": \"REVISE\"}"))
                .correct(runId, claim, Verdict.UNSUPPORTED);

        assertThat(outcome.kind()).isEqualTo(ClaimCorrector.Kind.REMOVED);
    }

    @Test
    void anUnreadableDecisionLeavesTheClaimUnchanged() {
        ClaimCorrector.Outcome outcome = corrector(decides("not json at all"))
                .correct(runId, claim, Verdict.CONTRADICTED);

        assertThat(outcome.kind()).isEqualTo(ClaimCorrector.Kind.UNCHANGED);
    }

    @Test
    void noLlmBudgetLeavesTheClaimUnchanged() {
        when(guard.tryLlmCall(runId)).thenReturn(false);

        ClaimCorrector.Outcome outcome = corrector(decides("{\"action\": \"DROP\"}"))
                .correct(runId, claim, Verdict.CONTRADICTED);

        assertThat(outcome.kind()).isEqualTo(ClaimCorrector.Kind.UNCHANGED);
    }

    @Test
    void noSearchBudgetWorksFromPagesAlreadyRead() {
        when(guard.trySearch(runId)).thenReturn(false);
        when(passages.retrieveTopK(eq(runId), anyString(), anyInt())).thenReturn(List.of(
                new Document("RBI kept the repo rate at 6.5 per cent.", Map.of("sourceUrl", KNOWN))));

        ClaimCorrector.Outcome outcome = corrector(decides(
                "{\"action\": \"REVISE\", \"text\": \"The repo rate is 6.5%.\", \"sourceUrl\": \"" + KNOWN + "\"}"))
                .correct(runId, claim, Verdict.CONTRADICTED);

        verify(retrieval, never()).search(anyList(), anyInt(), any(), anyInt());
        assertThat(outcome.kind()).isEqualTo(ClaimCorrector.Kind.REVISED);
    }

    @Test
    void aFailedSearchStillLetsTheRewriteUsePagesAlreadyRead() {
        when(retrieval.search(anyList(), anyInt(), any(), anyInt())).thenThrow(new RuntimeException("down"));
        when(passages.retrieveTopK(eq(runId), anyString(), anyInt())).thenReturn(List.of(
                new Document("RBI kept the repo rate at 6.5 per cent.", Map.of("sourceUrl", KNOWN))));

        ClaimCorrector.Outcome outcome = corrector(decides("{\"action\": \"DROP\"}"))
                .correct(runId, claim, Verdict.CONTRADICTED);

        assertThat(outcome.kind()).isEqualTo(ClaimCorrector.Kind.REMOVED);
    }

    @Test
    void aSearchWithNothingNewSkipsTheFetch() {
        when(retrieval.search(anyList(), anyInt(), any(), anyInt())).thenReturn(new SearchResponse(List.of(
                new SearchResult(KNOWN, "already read", "", 1)), 1, 0));

        corrector(decides("{\"action\": \"DROP\"}")).correct(runId, claim, Verdict.CONTRADICTED);

        verify(retrieval, never()).extract(anyList());
    }
}
