package com.comeback.researchplatform.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * The one {@code guardrails:} tree every limit in this project is supposed
 * to read from (CLAUDE.md: "never a hardcoded constant, never a scattered
 * @Value"). Bound once here, in {@code common}, so all three services share
 * one shape and one {@link GuardrailMode} switch.
 * <p>
 * Honest status, not silently glossed over: {@code retrieval.quotaDailyLimit}
 * is the one field actually wired to a real enforcement point (see
 * QuotaEnforcementService in retrieval-service). {@code run} and {@code
 * agent} mirror PLAN's config shape but are not yet the live source for
 * ResearcherProperties/CriticProperties/PlannerProperties/RateLimitProperties
 * -- those pre-date this tree, already work, and were left alone rather than
 * risk a late, large refactor. {@code eval} is read by the fabrication
 * eval. Consolidating everything onto this one tree is real remaining work,
 * named here rather than pretended-away.
 */
@ConfigurationProperties(prefix = "guardrails")
public record GuardrailProperties(
        GuardrailMode mode,
        Run run,
        Agent agent,
        Retrieval retrieval,
        Eval eval
) {
    public record Run(
            Duration maxWallClock,
            int maxSearches,
            int maxLlmCalls,
            int maxSubquestions,
            int maxCriticRounds,
            String onBreach
    ) {}

    public record Agent(Researcher researcher) {
        public record Researcher(
                Duration maxWallClock,
                int maxTokens,
                int maxSearches,
                String onBreach
        ) {}
    }

    public record Retrieval(
            int quotaDailyLimit,
            double perDomainRps,
            int burst,
            int extractorPermits,
            int fetchParallelism,
            Duration connectTimeout,
            Duration readTimeout,
            int maxDocumentBytes,
            boolean blockPrivateNetworks,
            List<String> allowedSchemes
    ) {}

    public record Eval(double minCatchRate, double maxFalsePositiveRate) {}
}
