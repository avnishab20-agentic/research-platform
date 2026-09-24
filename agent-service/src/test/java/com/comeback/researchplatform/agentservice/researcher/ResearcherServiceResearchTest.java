package com.comeback.researchplatform.agentservice.researcher;

import com.comeback.researchplatform.agentservice.activity.RunActivityLog;
import com.comeback.researchplatform.agentservice.chat.ScriptedChatModel;
import com.comeback.researchplatform.agentservice.config.ResearcherProperties;
import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import com.comeback.researchplatform.agentservice.guardrail.RunUsageGuard;
import com.comeback.researchplatform.agentservice.rag.PassageStore;
import com.comeback.researchplatform.agentservice.retrieval.RetrievalClient;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractResponse;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractedDocument;
import com.comeback.researchplatform.agentservice.retrieval.dto.SearchResponse;
import com.comeback.researchplatform.agentservice.retrieval.dto.SearchResult;
import com.comeback.researchplatform.common.ResearchFinding;
import com.comeback.researchplatform.common.ResearchSubtask;
import com.comeback.researchplatform.common.SourceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs research() from start to finish: the LLM is a {@link ScriptedChatModel},
 * retrieval and the vector store are mocks. Each test changes one step to see
 * how the researcher reacts.
 */
class ResearcherServiceResearchTest {

    private static final String RBI = "https://rbi.org.in/repo-rate";
    private static final String BLOG = "https://someone.blogspot.com/post";

    private final UUID runId = UUID.randomUUID();
    private final ResearchSubtask subtask =
            new ResearchSubtask(runId, UUID.randomUUID(), 0, "What is India's repo rate?");

    private ScriptedChatModel chat;
    private RetrievalClient retrieval;
    private PassageStore passages;
    private RunUsageGuard guard;
    private ResearcherProperties props;

    @BeforeEach
    void setUp() {
        chat = new ScriptedChatModel()
                .reply("You generate web search queries", "india repo rate\n\nrbi policy rate\n")
                .reply("Answer the research question", "The repo rate is 6.5%.");
        retrieval = mock(RetrievalClient.class);
        passages = mock(PassageStore.class);
        guard = mock(RunUsageGuard.class);
        when(guard.tryLlmCall(runId)).thenReturn(true);
        when(guard.trySearch(runId)).thenReturn(true);
        props = new ResearcherProperties(Duration.ofSeconds(90), 25000, 3, 5, 6);

        // The same page twice (two queries found it) plus one other page.
        when(retrieval.search(anyList(), anyInt(), any(), anyInt())).thenReturn(new SearchResponse(List.of(
                new SearchResult(RBI, "RBI", "snippet", 1),
                new SearchResult(RBI, "RBI again", "snippet", 1),
                new SearchResult(BLOG, "Blog", "snippet", 4)), 1, 0));
        when(retrieval.extract(anyList())).thenReturn(new ExtractResponse(List.of(
                new ExtractedDocument(RBI, "The policy repo rate is 6.5 per cent.", 1, "OK"),
                new ExtractedDocument(BLOG, null, 4, "PAYWALLED"))));
        when(passages.retrieveTopK(eq(runId), anyString(), anyInt())).thenReturn(List.of(
                new Document("The policy repo rate is 6.5 per cent.", Map.of("sourceUrl", RBI))));
    }

    private ResearcherService service() {
        return new ResearcherService(chat.builder(), retrieval, passages, props, mock(FixtureIO.class),
                guard, mock(RunActivityLog.class));
    }

    @Test
    void aFullRunProducesACompleteFindingCitingOnlyTheSourceItUsed() {
        ResearchFinding finding = service().research(subtask);

        assertThat(finding.status()).isEqualTo("COMPLETE");
        assertThat(finding.answer()).isEqualTo("The repo rate is 6.5%.");
        assertThat(finding.sources()).containsExactly(new SourceRef(RBI, 1));
        assertThat(finding.confidence()).isEqualTo(0.95);
    }

    @Test
    void duplicateSearchResultsAreExtractedOnce() {
        service().research(subtask);

        verify(retrieval).extract(List.of(RBI, BLOG));
    }

    @Test
    void blankLinesInTheModelsQueryListAreDropped() {
        service().research(subtask);

        verify(retrieval).search(eq(List.of("india repo rate", "rbi policy rate")), anyInt(), any(), anyInt());
    }

    @Test
    void onlyReadablePagesAreIndexed() {
        service().research(subtask);

        verify(passages).index(runId, RBI, "The policy repo rate is 6.5 per cent.");
        verify(passages, never()).index(eq(runId), eq(BLOG), any());
    }

    @Test
    void anUnanswerableReplyGivesAPartialFindingWithZeroConfidence() {
        chat = new ScriptedChatModel()
                .reply("You generate web search queries", "india repo rate")
                .reply("Answer the research question", "UNANSWERABLE");

        ResearchFinding finding = service().research(subtask);

        assertThat(finding.status()).isEqualTo("PARTIAL");
        assertThat(finding.confidence()).isZero();
        assertThat(finding.answer()).contains("did not contain an answer");
    }

    @Test
    void noLlmBudgetForTheAnswerStillPublishesAPartialFinding() {
        // First call (queries) allowed, second (answer) refused.
        when(guard.tryLlmCall(runId)).thenReturn(true, false);

        ResearchFinding finding = service().research(subtask);

        assertThat(finding.status()).isEqualTo("PARTIAL");
        assertThat(finding.answer()).isEqualTo("Unable to synthesize an answer within budget.");
    }

    @Test
    void noLlmBudgetForQueriesStopsBeforeSearching() {
        when(guard.tryLlmCall(runId)).thenReturn(false);

        ResearchFinding finding = service().research(subtask);

        assertThat(finding.status()).isEqualTo("PARTIAL");
        verify(retrieval, never()).search(anyList(), anyInt(), any(), anyInt());
    }

    @Test
    void noReadablePagesStopsBeforeTheAnswer() {
        when(retrieval.extract(anyList())).thenReturn(new ExtractResponse(List.of(
                new ExtractedDocument(RBI, "", 1, "OK"))));

        ResearchFinding finding = service().research(subtask);

        assertThat(finding.status()).isEqualTo("PARTIAL");
        assertThat(finding.answer()).isEqualTo("No extractable sources found for this sub-question.");
    }

    @Test
    void aFailedSearchIsTreatedAsNoResults() {
        when(retrieval.search(anyList(), anyInt(), any(), anyInt())).thenThrow(new RuntimeException("down"));

        ResearchFinding finding = service().research(subtask);

        assertThat(finding.status()).isEqualTo("PARTIAL");
        assertThat(finding.sources()).isEmpty();
    }

    @Test
    void theSearchBudgetBeingUsedUpMeansNoResults() {
        when(guard.trySearch(runId)).thenReturn(false);

        ResearchFinding finding = service().research(subtask);

        assertThat(finding.status()).isEqualTo("PARTIAL");
        verify(retrieval, never()).extract(anyList());
    }

    @Test
    void aFailedExtractIsTreatedAsNoPages() {
        when(retrieval.extract(anyList())).thenThrow(new RuntimeException("down"));

        assertThat(service().research(subtask).status()).isEqualTo("PARTIAL");
    }

    @Test
    void noRelevantPassagesStopsBeforeTheAnswer() {
        when(passages.retrieveTopK(eq(runId), anyString(), anyInt())).thenReturn(List.of());

        ResearchFinding finding = service().research(subtask);

        assertThat(finding.answer()).isEqualTo("No relevant passages retrieved for this sub-question.");
    }

    @Test
    void onlyAsManyPagesAsConfiguredAreExtracted() {
        props = new ResearcherProperties(Duration.ofSeconds(90), 25000, 3, 1, 6);

        service().research(subtask);

        verify(retrieval).extract(List.of(RBI));
    }
}
