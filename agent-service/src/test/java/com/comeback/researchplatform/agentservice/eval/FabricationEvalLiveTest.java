package com.comeback.researchplatform.agentservice.eval;

import com.comeback.researchplatform.common.Verdict;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link FabricationEval} against the REAL DeepSeek-backed critic --
 * not the scripted grader {@code FabricationEvalTest} uses to prove its own
 * bookkeeping. This is the actual measurement PLAN Week 4 asks for: catch
 * rate > 0.85, false-positive rate < 0.10, both directions checked.
 * <p>
 * {@code @Disabled} by default: it costs real DeepSeek calls, needs
 * DEEPSEEK_API_KEY and a live Postgres with a real verified run's claims
 * already in it (not fixture data, not CI-safe), so it's a manually-run
 * measurement, not part of the ordinary `mvn test` suite. Enable by
 * commenting out {@code @Disabled} and running directly.
 * <p>
 * The grading prompt below is deliberately kept identical to
 * {@link com.comeback.researchplatform.agentservice.critic.CriticService}'s
 * real one (single-claim batches, same system prompt, same parsing) so this
 * measures the actual production grading behavior, not an approximation of
 * it.
 */
@SpringBootTest
class FabricationEvalLiveTest {

    // A real VERIFIED run with a large SUPPORTED pool -- see docs/progress
    // for how this run was produced (India renewable energy policy question,
    // 62 claims, 48 graded SUPPORTED by the live critic).
    private static final String RUN_ID = "4c34839e-b413-417f-9b39-7ef4446e41e7";

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Disabled("costs real DeepSeek calls -- run manually, see class javadoc")
    void catchesFabricatedClaimsInARealRun() {
        List<SourcedClaim> knownGood = loadSupportedClaims(RUN_ID);
        assertThat(knownGood).isNotEmpty();

        ChatClient chatClient = chatClientBuilder.build();
        ClaimGrader grader = (claimText, evidence) -> gradeLive(chatClient, claimText, evidence);

        int corruptCount = Math.min(20, knownGood.size() / 2);
        FabricationEvalResult result = new FabricationEval().run(knownGood, corruptCount, grader);

        System.out.println("=== Fabrication-injection eval (live DeepSeek critic) ===");
        System.out.println("corrupted: " + result.corruptedCount() + ", caught: " + result.corruptedCaught()
                + ", catch rate: " + result.catchRate());
        System.out.println("clean: " + result.cleanCount() + ", flagged: " + result.cleanFlagged()
                + ", false-positive rate: " + result.falsePositiveRate());
        System.out.println("passes(0.85, 0.10): " + result.passes(0.85, 0.10));

        assertThat(result.catchRate()).isGreaterThan(0.85);
        assertThat(result.falsePositiveRate()).isLessThan(0.10);
    }

    // KNOWN LIMITATION, found by actually running this: evidence here is the single
    // short quoted sentence claim_verdicts stored from the ORIGINAL grading pass, not
    // the full multi-passage RAG retrieval CriticService.gradeBatch uses live
    // (passageStore.retrieveTopK, several passages per claim). A first real run
    // (2026-09-22) measured catch rate 1.0 but false-positive rate 0.5 against 0.10 --
    // plausibly an artifact of this thinner evidence rather than a real critic quality
    // problem, since a single short sentence often lacks enough context to fully
    // support a claim on its own. Re-running with real retrieveTopK passages instead
    // of the stored quote is the fair next measurement, not yet done.
    private List<SourcedClaim> loadSupportedClaims(String runId) {
        return jdbc.query(
                "SELECT c.text, cv.evidence_passage, s.url FROM claims c "
                        + "JOIN claim_verdicts cv ON cv.claim_id = c.id "
                        + "JOIN sources s ON s.id = c.source_id "
                        + "WHERE c.run_id = ? AND cv.verdict = 'SUPPORTED'",
                (rs, rowNum) -> new SourcedClaim(
                        rs.getString("text"), List.of(rs.getString("evidence_passage")), rs.getString("url")),
                UUID.fromString(runId));
    }

    // Same system prompt and batch-of-one shape as CriticService.gradeBatch --
    // this is what makes the measurement about the real production grader,
    // not a differently-worded stand-in.
    private Verdict gradeLive(ChatClient chatClient, String claimText, List<String> evidence) {
        String system = "You are a fact-checker. For each numbered claim, decide whether the "
                + "evidence passages support it: SUPPORTED (fully backed), PARTIAL "
                + "(backed but missing detail or nuance), UNSUPPORTED (evidence doesn't "
                + "address the claim), or CONTRADICTED (evidence directly disagrees). "
                + "For each claim, quote the exact sentence from its evidence that most "
                + "influenced your decision -- copy it verbatim, don't paraphrase.";
        StringBuilder user = new StringBuilder();
        user.append("Claim 1: ").append(claimText).append("\nEvidence passages:\n");
        for (String passage : evidence) {
            user.append("- ").append(passage).append("\n");
        }
        ResponseEntity<ChatResponse, List<ClaimGradeDto>> result = chatClient.prompt()
                .system(system)
                .user(user.toString())
                .call()
                .responseEntity(new ParameterizedTypeReference<List<ClaimGradeDto>>() {});
        List<ClaimGradeDto> grades = result.entity();
        if (grades == null || grades.isEmpty()) {
            return Verdict.UNREACHABLE;
        }
        return grades.get(0).verdict();
    }

    record ClaimGradeDto(int index, Verdict verdict, String evidencePassage) {}
}
