package com.comeback.researchplatform.agentservice.eval;

import com.comeback.researchplatform.common.Verdict;

import java.util.List;

/**
 * The one seam FabricationEval needs to be pointed at a real grader: given
 * a claim's text and the evidence passages retrieved for it, produce a
 * verdict. CriticService's real grading (batched, via DeepSeek or its
 * fixture-mode replay) implements this for a live/fixture eval run;
 * FabricationEvalTest uses a small deterministic simulation to prove the
 * eval's own bookkeeping (corruption application, catch-rate/false-
 * positive-rate math, threshold comparison) is correct independent of
 * whether a real model is available.
 */
public interface ClaimGrader {
    Verdict grade(String claimText, List<String> evidencePassages);
}
