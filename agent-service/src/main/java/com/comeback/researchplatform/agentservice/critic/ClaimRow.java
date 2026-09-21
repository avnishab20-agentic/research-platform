package com.comeback.researchplatform.agentservice.critic;

import java.util.UUID;

/** One row of claims JOIN sources, loaded for grading. */
record ClaimRow(UUID id, String text, String kind, UUID sourceId, String sourceUrl) {}
