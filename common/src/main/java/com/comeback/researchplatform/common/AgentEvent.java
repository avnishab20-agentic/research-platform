package com.comeback.researchplatform.common;

import java.time.Instant;
import java.util.UUID;

/**
 * A progress event published to {@code agent.events} and relayed to the browser
 * over SSE — "sub-question 3 of 7 started", "critic round 1 complete", etc.
 * {@code payload} is a small JSON blob whose shape depends on {@code type}; kept
 * as a String rather than a sealed hierarchy so new event types don't need a
 * schema change here.
 */
public record AgentEvent(
        UUID runId,
        String type,
        String payload,
        Instant timestamp
) {}
