package com.comeback.researchplatform.controlplane.web;

import java.util.UUID;

/** One run_events row, streamed over SSE as an "activity" event. nodeId is
 *  set for researcher lines and null for planner/writer/critic lines. */
public record ActivityView(long id, UUID nodeId, String agent, String message) {}
