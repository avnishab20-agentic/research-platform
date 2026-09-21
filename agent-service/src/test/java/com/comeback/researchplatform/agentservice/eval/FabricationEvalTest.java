package com.comeback.researchplatform.agentservice.eval;

import com.comeback.researchplatform.common.Verdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves FabricationEval's own bookkeeping (which claims get corrupted, the
 * catch-rate/false-positive-rate arithmetic, the pass/fail threshold check)
 * is correct -- independent of whether a real model is available. The
 * grader here is fully scripted, not a simulation of real Critic behavior:
 * it returns a pre-determined sequence of verdicts so every number in this
 * test can be hand-checked exactly. A genuine measurement of DeepSeek's
 * real catch rate needs CriticService wired in as the ClaimGrader, live or
 * via fixture replay -- not yet possible today (see docs/progress, the
 * SearXNG rate-limit entry) but this class is ready for it the moment real
 * grading data exists.
 */
class FabricationEvalTest {

    /** Claims deliberately built so every corruption type in FabricationEval's
     *  cycle definitely applies -- a number, a polarity word, a proper noun,
     *  a hedge phrase, and a source URL for FABRICATE. */
    private static List<SourcedClaim> fiveCorruptibleClaims() {
        return List.of(
                new SourcedClaim("Revenue grew to $4.2B last quarter.",
                        List.of("Company reported revenue of $4.2B."), "https://example.com/a"),
                new SourcedClaim("Sales increased sharply this year.",
                        List.of("Sales figures increased this year."), "https://example.com/b"),
                new SourcedClaim("Meridian Bank raised its policy rate.",
                        List.of("Meridian Bank announced a rate increase."), "https://example.com/c"),
                new SourcedClaim("Some analysts expect further gains.",
                        List.of("A few analysts commented on expected gains."), "https://example.com/d"),
                new SourcedClaim("The report was released on Tuesday.",
                        List.of("The report's release date was Tuesday."), "https://example.com/e"));
    }

    private static List<SourcedClaim> threeCleanClaims() {
        return List.of(
                new SourcedClaim("Inflation held steady at 3%.", List.of("Inflation was 3%."), "https://example.com/f"),
                new SourcedClaim("The bank kept rates unchanged.", List.of("Rates were unchanged."), "https://example.com/g"),
                new SourcedClaim("Exports rose modestly in Q2.", List.of("Q2 exports rose modestly."), "https://example.com/h"));
    }

    private static class ScriptedGrader implements ClaimGrader {
        private final Deque<Verdict> script;
        ScriptedGrader(List<Verdict> script) { this.script = new ArrayDeque<>(script); }
        @Override public Verdict grade(String claimText, List<String> evidencePassages) {
            return script.poll();
        }
    }

    @Test
    void allFiveCorruptionTypesApplyToTheTestFixtures() {
        // Guards the fixtures themselves -- if a future edit to ClaimCorruptor
        // stops matching one of these claims, this fails loudly instead of the
        // eval below silently corrupting fewer than 5 claims.
        List<SourcedClaim> claims = fiveCorruptibleClaims();
        FabricationEval eval = new FabricationEval();
        // A grader that always says SUPPORTED makes every non-empty
        // corruption attempt count as "not caught" -- corruptedCount below
        // tells us how many of the 5 corruptions actually applied.
        FabricationEvalResult result = eval.run(claims, 5, (text, evidence) -> Verdict.SUPPORTED);

        assertThat(result.corruptedCount()).isEqualTo(5);
    }

    @Test
    void perfectGraderScoresFullCatchRateAndZeroFalsePositives() {
        List<SourcedClaim> pool = concat(fiveCorruptibleClaims(), threeCleanClaims());
        FabricationEval eval = new FabricationEval();

        FabricationEvalResult result = eval.run(pool, 5,
                (text, evidence) -> Verdict.UNSUPPORTED); // flags everything -- "catches" all 5, and the 3 clean too

        // Not a realistic grader (PLAN: "a Critic that flags everything scores
        // a perfect catch rate and is useless") -- this is exactly why both
        // numbers are checked, not just catch rate alone.
        assertThat(result.catchRate()).isEqualTo(1.0);
        assertThat(result.falsePositiveRate()).isEqualTo(1.0);
        assertThat(result.passes(0.85, 0.10)).isFalse(); // fails on false positives
    }

    @Test
    void catchRateAndFalsePositiveRateAreComputedIndependently() {
        List<SourcedClaim> pool = concat(fiveCorruptibleClaims(), threeCleanClaims());
        FabricationEval eval = new FabricationEval();

        // Script: 4 of 5 corrupted claims caught (UNSUPPORTED), 1 missed
        // (SUPPORTED); 0 of 3 clean claims flagged.
        ScriptedGrader grader = new ScriptedGrader(List.of(
                Verdict.UNSUPPORTED, Verdict.UNSUPPORTED, Verdict.UNSUPPORTED, Verdict.UNSUPPORTED, Verdict.SUPPORTED,
                Verdict.SUPPORTED, Verdict.SUPPORTED, Verdict.SUPPORTED));

        FabricationEvalResult result = eval.run(pool, 5, grader);

        assertThat(result.corruptedCount()).isEqualTo(5);
        assertThat(result.corruptedCaught()).isEqualTo(4);
        assertThat(result.catchRate()).isEqualTo(0.8);
        assertThat(result.cleanCount()).isEqualTo(3);
        assertThat(result.cleanFlagged()).isEqualTo(0);
        assertThat(result.falsePositiveRate()).isEqualTo(0.0);
        assertThat(result.passes(0.85, 0.10)).isFalse(); // 0.8 < 0.85 threshold
    }

    @Test
    void meetsThresholdWhenBothNumbersClearTheBar() {
        List<SourcedClaim> pool = concat(fiveCorruptibleClaims(), threeCleanClaims());
        FabricationEval eval = new FabricationEval();

        // All 5 corrupted caught, 0 of 3 clean flagged.
        ScriptedGrader grader = new ScriptedGrader(List.of(
                Verdict.UNSUPPORTED, Verdict.CONTRADICTED, Verdict.UNSUPPORTED, Verdict.PARTIAL, Verdict.UNREACHABLE,
                Verdict.SUPPORTED, Verdict.SUPPORTED, Verdict.SUPPORTED));

        FabricationEvalResult result = eval.run(pool, 5, grader);

        assertThat(result.catchRate()).isEqualTo(1.0);
        assertThat(result.falsePositiveRate()).isEqualTo(0.0);
        assertThat(result.passes(0.85, 0.10)).isTrue();
    }

    @Test
    void rejectsCorruptingMoreClaimsThanArePresent() {
        List<SourcedClaim> pool = fiveCorruptibleClaims();
        FabricationEval eval = new FabricationEval();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> eval.run(pool, 10, (text, evidence) -> Verdict.SUPPORTED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<SourcedClaim> concat(List<SourcedClaim> a, List<SourcedClaim> b) {
        List<SourcedClaim> combined = new java.util.ArrayList<>(a);
        combined.addAll(b);
        return combined;
    }
}
