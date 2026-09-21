package com.comeback.researchplatform.common;

import java.util.UUID;

/**
 * A verdict plus the exact evidence passage it was graded against — the passage
 * is what makes the verdict checkable rather than an opaque Critic opinion.
 */
public record ClaimVerdict(
        UUID claimId,
        Verdict verdict,
        String evidencePassage
) {}
