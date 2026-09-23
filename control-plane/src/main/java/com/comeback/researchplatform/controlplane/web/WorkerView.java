package com.comeback.researchplatform.controlplane.web;

import java.util.UUID;

/** One researcher's real sub-question and dag_nodes.status ("PENDING" until
 *  a finding lands, then "COMPLETE" or "PARTIAL"). nodeId lets the browser
 *  attach each ActivityView line to the researcher that wrote it. */
public record WorkerView(UUID nodeId, String subQuestion, String state) {}
