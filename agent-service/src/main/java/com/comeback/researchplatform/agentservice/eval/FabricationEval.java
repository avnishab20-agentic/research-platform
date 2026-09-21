package com.comeback.researchplatform.agentservice.eval;

import com.comeback.researchplatform.common.Verdict;

import java.util.List;

/**
 * PLAN's fabrication-injection eval, Week 4's differentiator: start from
 * claims already known to be SUPPORTED, corrupt some of them, grade
 * everything again, and measure whether the Critic actually catches the
 * corrupted ones without also flagging the clean ones. Runs against
 * whatever {@link ClaimGrader} it's given -- the real Critic (live or
 * fixture-replayed) for a genuine measurement, or a simulated grader (see
 * {@code FabricationEvalTest}) to prove this class's own bookkeeping is
 * correct independent of model behavior.
 */
public class FabricationEval {

    private static final CorruptionType[] CYCLE = {
            CorruptionType.SWAP_NUMBER, CorruptionType.INVERT, CorruptionType.SWAP_ENTITY,
            CorruptionType.OVERREACH, CorruptionType.FABRICATE
    };

    public FabricationEvalResult run(List<SourcedClaim> knownGoodClaims, int corruptCount, ClaimGrader grader) {
        if (corruptCount > knownGoodClaims.size()) {
            throw new IllegalArgumentException(
                    "Cannot corrupt " + corruptCount + " claims from a pool of only " + knownGoodClaims.size());
        }

        List<SourcedClaim> toCorrupt = knownGoodClaims.subList(0, corruptCount);
        List<SourcedClaim> clean = knownGoodClaims.subList(corruptCount, knownGoodClaims.size());

        int corruptedCaught = 0;
        int actuallyCorrupted = 0;
        for (int i = 0; i < toCorrupt.size(); i++) {
            SourcedClaim original = toCorrupt.get(i);
            CorruptionType type = CYCLE[i % CYCLE.length];
            String corruptedText = applyCorruption(original, type);
            if (corruptedText == null) {
                // This corruption type didn't apply to this claim (e.g. no
                // number to swap) -- skip rather than silently grading the
                // unchanged original as if it were corrupted, which would
                // inflate the apparent catch rate.
                continue;
            }
            actuallyCorrupted++;
            Verdict verdict = grader.grade(corruptedText, original.evidence());
            if (verdict != Verdict.SUPPORTED) {
                corruptedCaught++;
            }
        }

        int cleanFlagged = 0;
        for (SourcedClaim claim : clean) {
            Verdict verdict = grader.grade(claim.text(), claim.evidence());
            if (verdict != Verdict.SUPPORTED) {
                cleanFlagged++;
            }
        }

        double catchRate = actuallyCorrupted == 0 ? 0.0 : (double) corruptedCaught / actuallyCorrupted;
        double falsePositiveRate = clean.isEmpty() ? 0.0 : (double) cleanFlagged / clean.size();

        return new FabricationEvalResult(actuallyCorrupted, corruptedCaught, clean.size(), cleanFlagged,
                catchRate, falsePositiveRate);
    }

    private String applyCorruption(SourcedClaim claim, CorruptionType type) {
        if (type == CorruptionType.FABRICATE) {
            return ClaimCorruptor.fabricate(claim.sourceUrl());
        }
        return ClaimCorruptor.corrupt(claim.text(), type).orElse(null);
    }
}
