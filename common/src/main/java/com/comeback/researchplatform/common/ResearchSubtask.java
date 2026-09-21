package com.comeback.researchplatform.common;

import java.util.UUID;

/**
 * One flat fan-out unit, published by the planner to {@code research.subtasks}.
 * {@code level} is always 0 this month — the column exists in {@code dag_nodes}
 * for a future DAG that weeks 1-4 deliberately don't build.
 */
public record ResearchSubtask(
        UUID runId,
        UUID nodeId,
        int level,
        String subQuestion
) {}
