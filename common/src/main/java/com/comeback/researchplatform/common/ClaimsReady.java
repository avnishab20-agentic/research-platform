package com.comeback.researchplatform.common;

import java.util.UUID;

/** Published by the WRITER once a run's claims are persisted; consumed by
 *  agent-service's CRITIC profile. */
public record ClaimsReady(UUID runId) {}
