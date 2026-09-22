package com.comeback.researchplatform.controlplane.web;

/** One researcher's real sub-question and dag_nodes.status ("PENDING" until
 *  a finding lands, then "COMPLETE" or "PARTIAL") -- replaces the old
 *  scripted per-worker percentage bars with actual per-node state. There is
 *  no in-between "running" signal in Postgres today (dag_nodes only flips
 *  once, on finding arrival), so a worker is either queued or done. */
public record WorkerView(String subQuestion, String state) {}
