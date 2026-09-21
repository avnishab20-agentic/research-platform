package com.comeback.researchplatform.agentservice.critic;

import com.comeback.researchplatform.agentservice.rag.PassageStore;
import com.comeback.researchplatform.agentservice.retrieval.RetrievalClient;
import com.comeback.researchplatform.common.Verdict;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Same reasoning as ResearcherServiceTest: only unsupportedRatio()'s pure
 * arithmetic is under test. This is PLAN's exact formula --
 * (UNSUPPORTED + CONTRADICTED) / verifiable -- and it's the one number that
 * decides VERIFIED vs UNVERIFIED, so it's worth pinning directly rather than
 * trusting only the one live run that happened to compute it correctly.
 */
class CriticServiceTest {

    private final CriticService service = new CriticService(
            mock(ChatClient.Builder.class), mock(RetrievalClient.class), mock(PassageStore.class),
            mock(JdbcTemplate.class), new CriticProperties(10, 3, 0.15));

    @Test
    void allSupportedGivesZeroRatio() {
        assertThat(service.unsupportedRatio(List.of(Verdict.SUPPORTED, Verdict.SUPPORTED))).isEqualTo(0.0);
    }

    @Test
    void allUnsupportedGivesOneRatio() {
        assertThat(service.unsupportedRatio(List.of(Verdict.UNSUPPORTED, Verdict.UNSUPPORTED))).isEqualTo(1.0);
    }

    @Test
    void contradictedCountsAsUnsupportedToo() {
        // PLAN's formula: (UNSUPPORTED + CONTRADICTED) / verifiable -- both
        // count against the ratio, not just UNSUPPORTED alone.
        assertThat(service.unsupportedRatio(List.of(Verdict.CONTRADICTED, Verdict.SUPPORTED))).isEqualTo(0.5);
    }

    @Test
    void partialAndUnreachableDoNotCountAsUnsupported() {
        // Real live case from the 2026-09-22 run: 6 PARTIAL among 59 graded
        // claims did not push the ratio over threshold, and shouldn't --
        // PARTIAL means "backed but missing nuance", not "wrong".
        List<Verdict> verdicts = List.of(Verdict.PARTIAL, Verdict.UNREACHABLE, Verdict.SUPPORTED, Verdict.SUPPORTED);
        assertThat(service.unsupportedRatio(verdicts)).isEqualTo(0.0);
    }

    @Test
    void emptyVerdictsGivesZeroRatioNotDivideByZero() {
        assertThat(service.unsupportedRatio(List.of())).isEqualTo(0.0);
    }

    @Test
    void matchesTheThresholdBoundaryFromRealRun() {
        // The 2026-09-22 live run: 5 bad out of 59 graded = ~0.0847, under
        // the 0.15 threshold. Pinned so a future change to this arithmetic
        // can be checked against a real, previously-verified outcome.
        List<Verdict> verdicts = new java.util.ArrayList<>();
        for (int i = 0; i < 48; i++) verdicts.add(Verdict.SUPPORTED);
        for (int i = 0; i < 6; i++) verdicts.add(Verdict.PARTIAL);
        for (int i = 0; i < 5; i++) verdicts.add(Verdict.UNSUPPORTED);
        assertThat(service.unsupportedRatio(verdicts)).isCloseTo(0.0847, org.assertj.core.data.Offset.offset(0.001));
    }
}
