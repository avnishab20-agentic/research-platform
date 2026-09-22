package com.comeback.researchplatform.controlplane.web;

import java.util.List;

/** One tick of the SSE stream -- runs.status, dag_levels' fan-in counters,
 *  and each sub-question's real state, so the browser can show "N of M
 *  researchers finished" plus which ones instead of a bare status string. */
public record RunProgressEvent(String status, int completed, int expected, List<WorkerView> workers) {}
