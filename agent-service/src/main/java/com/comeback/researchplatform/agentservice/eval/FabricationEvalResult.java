package com.comeback.researchplatform.agentservice.eval;

/**
 * PLAN's two numbers: catch rate must clear {@code guardrails.eval.min-catch-rate}
 * (target >0.85) and false-positive rate must stay under {@code
 * max-false-positive-rate} (target <0.10). Both directions matter together --
 * PLAN: "a Critic that flags everything scores a perfect catch rate and is
 * useless."
 */
public record FabricationEvalResult(
        int corruptedCount,
        int corruptedCaught,
        int cleanCount,
        int cleanFlagged,
        double catchRate,
        double falsePositiveRate
) {
    public boolean passes(double minCatchRate, double maxFalsePositiveRate) {
        return catchRate >= minCatchRate && falsePositiveRate <= maxFalsePositiveRate;
    }
}
