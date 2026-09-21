package com.comeback.researchplatform.agentservice.researcher;

import com.comeback.researchplatform.agentservice.config.ResearcherProperties;
import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import com.comeback.researchplatform.agentservice.rag.PassageStore;
import com.comeback.researchplatform.agentservice.retrieval.RetrievalClient;
import com.comeback.researchplatform.common.SourceRef;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ChatClient/RetrievalClient/PassageStore are all real Spring AI/HTTP fluent
 * APIs -- mocked only enough to construct the service, never invoked. What's
 * actually under test is confidenceFor()'s pure arithmetic, exactly the kind
 * of logic that produced a real live bug (Session 2026-09-22: a finding that
 * correctly declined to answer still scored 0.95 confidence, because this
 * method only ever looked at tier, never at whether an answer was given).
 */
class ResearcherServiceTest {

    private final ResearcherService service = new ResearcherService(
            mock(ChatClient.Builder.class), mock(RetrievalClient.class), mock(PassageStore.class),
            new ResearcherProperties(Duration.ofSeconds(90), 25000, 5, 5, 6),
            mock(FixtureIO.class), false);

    @Test
    void tier1SourceScoresHighestConfidence() {
        assertThat(service.confidenceFor(List.of(new SourceRef("https://rbi.org.in/x", 1)))).isEqualTo(0.95);
    }

    @Test
    void tier2SourceScoresSecondHighest() {
        assertThat(service.confidenceFor(List.of(new SourceRef("https://thehindu.com/x", 2)))).isEqualTo(0.85);
    }

    @Test
    void tier3SourceScoresDowngraded() {
        assertThat(service.confidenceFor(List.of(new SourceRef("https://random-blog.com/x", 3)))).isEqualTo(0.6);
    }

    @Test
    void tier4SourceScoresLowest() {
        assertThat(service.confidenceFor(List.of(new SourceRef("https://random-blog.com/x", 4)))).isEqualTo(0.4);
    }

    @Test
    void bestTierAmongMultipleSourcesWins() {
        List<SourceRef> sources = List.of(
                new SourceRef("https://tier4.example", 4),
                new SourceRef("https://rbi.org.in/x", 1),
                new SourceRef("https://tier3.example", 3));
        assertThat(service.confidenceFor(sources)).isEqualTo(0.95);
    }

    @Test
    void noSourcesScoreZero() {
        assertThat(service.confidenceFor(List.of())).isEqualTo(0.0);
    }
}
