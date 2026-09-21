package com.comeback.researchplatform.common;

import java.util.List;
import java.util.UUID;

/**
 * One researcher's result for one subtask, published to {@code research.findings}.
 * {@code status} is COMPLETE or PARTIAL — a budget breach (90s wall clock, 25k
 * tokens, search budget) still emits a finding, never drops one silently.
 */
public record ResearchFinding(
        UUID runId,
        UUID nodeId,
        String subQuestion,
        String answer,
        List<SourceRef> sources,
        double confidence,
        String status
) {}
