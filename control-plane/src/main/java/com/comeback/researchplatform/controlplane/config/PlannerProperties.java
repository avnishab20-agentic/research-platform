package com.comeback.researchplatform.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * Interim planner/fan-in limits -- same "not yet the shared guardrails.*
 * tree" caveat as agent-service's ResearcherProperties. maxFanOut is
 * PLAN's guardrail item 5 ("planner fan-out cap"); levelDeadline is what
 * the sweeper compares dag_levels.deadline against.
 */
@ConfigurationProperties(prefix = "planner")
public record PlannerProperties(int minFanOut, int maxFanOut, Duration levelDeadline) {}
