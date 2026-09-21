package com.comeback.researchplatform.common;

import java.util.UUID;

/** Published by control-plane's fan-in the instant a run's research phase
 *  completes; consumed by agent-service's WRITER profile. */
public record RunReady(UUID runId) {}
