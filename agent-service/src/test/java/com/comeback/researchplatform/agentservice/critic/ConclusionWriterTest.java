package com.comeback.researchplatform.agentservice.critic;

import com.comeback.researchplatform.agentservice.critic.ConclusionWriter.Conclusion;
import com.comeback.researchplatform.agentservice.critic.ConclusionWriter.Draft;
import com.comeback.researchplatform.agentservice.critic.ConclusionWriter.DraftTakeaway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The citation filter is what keeps the conclusion from adding unchecked facts. */
class ConclusionWriterTest {

    private final ClaimRow a = new ClaimRow(UUID.randomUUID(), "a", "FACT", null, "u");
    private final ClaimRow b = new ClaimRow(UUID.randomUUID(), "b", "FACT", null, "u");
    private final List<ClaimRow> passed = List.of(a, b);

    @Test
    void citationsResolveToClaimIdsInOrderWithoutDuplicates() {
        Conclusion c = ConclusionWriter.resolve(new Draft("Yes.", List.of(2, 1, 2),
                List.of(new DraftTakeaway("point", List.of(1)))), passed);
        assertThat(c.answerClaimIds()).containsExactly(b.id(), a.id());
        assertThat(c.takeaways().get(0).claimIds()).containsExactly(a.id());
    }

    @Test
    void aTakeawayCitingNothingRealIsDropped() {
        Conclusion c = ConclusionWriter.resolve(new Draft("Yes.", List.of(1),
                List.of(new DraftTakeaway("invented", List.of(0, 3, 99)), new DraftTakeaway("uncited", null))), passed);
        assertThat(c.takeaways()).isEmpty();
    }

    @Test
    void aConclusionWithNoValidCitationAnywhereIsRejected() {
        assertThat(ConclusionWriter.resolve(new Draft("Yes.", List.of(7), List.of()), passed)).isNull();
        assertThat(ConclusionWriter.resolve(new Draft(" ", List.of(1), List.of()), passed)).isNull();
        assertThat(ConclusionWriter.resolve(null, passed)).isNull();
    }
}
