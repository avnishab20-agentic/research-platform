package com.comeback.researchplatform.common;

/**
 * Topic names shared by producers and consumers across services. Plain
 * constants only -- no Spring, no Kafka client classes -- so common stays
 * dependency-free per its own README.
 */
public final class KafkaTopics {

    public static final String RESEARCH_SUBTASKS = "research.subtasks";
    public static final String RESEARCH_FINDINGS = "research.findings";
    public static final String AGENT_EVENTS = "agent.events";
    // Added Week 3: the Writer/Critic handoff. Small, low-traffic control
    // topics -- one message per run, not per sub-question. Extends PLAN
    // Session 1's original 3 topics for the same reason those exist: a
    // durable, restart-survivable hop between two services (control-plane's
    // fan-in -> agent-service's WRITER profile -> agent-service's CRITIC
    // profile), matching CLAUDE.md's "Kafka for long-running/durable hops."
    public static final String RUN_READY = "run.ready";
    public static final String CLAIMS_READY = "claims.ready";

    private KafkaTopics() {}
}
