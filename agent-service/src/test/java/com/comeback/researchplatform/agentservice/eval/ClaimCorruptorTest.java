package com.comeback.researchplatform.agentservice.eval;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimCorruptorTest {

    // --- SWAP_NUMBER ---

    @Test
    void swapNumberChangesADollarFigure() {
        Optional<String> result = ClaimCorruptor.corrupt(
                "Revenue reached $4.2B in the last quarter.", CorruptionType.SWAP_NUMBER);

        assertThat(result).isPresent();
        assertThat(result.get()).isNotEqualTo("Revenue reached $4.2B in the last quarter.");
        assertThat(result.get()).contains("$"); // the currency sign survives the swap
    }

    @Test
    void swapNumberChangesAPercentage() {
        Optional<String> result = ClaimCorruptor.corrupt("Inflation is at 5.2%.", CorruptionType.SWAP_NUMBER);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("%");
        assertThat(result.get()).isNotEqualTo("Inflation is at 5.2%.");
    }

    @Test
    void swapNumberIsEmptyWhenThereIsNoNumber() {
        assertThat(ClaimCorruptor.corrupt("The company is headquartered in Tokyo.", CorruptionType.SWAP_NUMBER))
                .isEmpty();
    }

    // --- INVERT ---

    @Test
    void invertFlipsGrewToDeclined() {
        Optional<String> result = ClaimCorruptor.corrupt("Sales grew by 12% year over year.", CorruptionType.INVERT);

        assertThat(result).contains("Sales declined by 12% year over year.");
    }

    @Test
    void invertIsCaseInsensitiveButPreservesReplacementCase() {
        Optional<String> result = ClaimCorruptor.corrupt("Prices ROSE sharply.", CorruptionType.INVERT);

        assertThat(result).isPresent();
        assertThat(result.get()).containsIgnoringCase("fell");
    }

    @Test
    void invertIsEmptyWhenNoPolarityWordIsPresent() {
        assertThat(ClaimCorruptor.corrupt("The report was published on Tuesday.", CorruptionType.INVERT))
                .isEmpty();
    }

    // --- SWAP_ENTITY ---

    @Test
    void swapEntityReplacesTheLongestCapitalizedPhrase() {
        Optional<String> result = ClaimCorruptor.corrupt(
                "The Reserve Bank of India set the repo rate at 5.25%.", CorruptionType.SWAP_ENTITY);

        assertThat(result).isPresent();
        assertThat(result.get()).doesNotContain("Reserve Bank of India");
        assertThat(result.get()).contains("5.25%"); // only the entity changes, not the figure
    }

    @Test
    void swapEntityIsEmptyWhenThereIsNoProperNoun() {
        assertThat(ClaimCorruptor.corrupt("the rate increased last month.", CorruptionType.SWAP_ENTITY))
                .isEmpty();
    }

    // --- OVERREACH ---

    @Test
    void overreachEscalatesSomeAnalystsToUnanimous() {
        Optional<String> result = ClaimCorruptor.corrupt(
                "Some analysts expect a rate cut next quarter.", CorruptionType.OVERREACH);

        assertThat(result).contains("analysts unanimously expect a rate cut next quarter.");
    }

    @Test
    void overreachIsEmptyWhenNoHedgeWordIsPresent() {
        assertThat(ClaimCorruptor.corrupt("The central bank cut rates by 25 basis points.", CorruptionType.OVERREACH))
                .isEmpty();
    }

    // --- FABRICATE ---

    @Test
    void fabricateProducesANewClaimCitingTheGivenSource() {
        String fabricated = ClaimCorruptor.fabricate("https://rbi.org.in/report");

        assertThat(fabricated).contains("https://rbi.org.in/report");
    }

    @Test
    void corruptOfFabricateTypeIsAlwaysEmpty() {
        // FABRICATE isn't a mutation of existing text -- callers must use
        // fabricate() directly, not corrupt(text, FABRICATE).
        assertThat(ClaimCorruptor.corrupt("any claim text", CorruptionType.FABRICATE)).isEmpty();
    }
}
