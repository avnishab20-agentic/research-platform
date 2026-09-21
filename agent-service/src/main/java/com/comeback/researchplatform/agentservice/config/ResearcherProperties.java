package com.comeback.researchplatform.agentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * Interim, researcher-scoped limits -- not yet the shared guardrails.*
 * tree CLAUDE.md describes (that's PLAN's guardrail build-order item #1,
 * not done this session). Kept here, config-driven rather than a hardcoded
 * constant, so folding it into the shared tree later is a move, not a
 * rewrite.
 */
@ConfigurationProperties(prefix = "researcher")
public record ResearcherProperties(
        Duration wallClockBudget,
        int tokenBudget,
        int maxSearchQueries,
        int maxDocumentsToExtract,
        int passagesPerQuery
) {}
