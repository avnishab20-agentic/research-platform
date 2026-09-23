package com.comeback.researchplatform.agentservice.critic;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Interim Critic limits -- same "not yet the shared guardrails.* tree"
 * caveat as ResearcherProperties/PlannerProperties. unsupportedRatioThreshold
 * is PLAN's 0.15; batchSize is PLAN's "batched 10 claims per call".
 * maxCorrections caps the re-research round: each corrected claim costs one
 * search and one LLM call, on top of the run-level ceilings that still apply.
 */
@ConfigurationProperties(prefix = "critic")
public record CriticProperties(int batchSize, int topKPassages, double unsupportedRatioThreshold,
                               int maxCorrections) {}
