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

    private KafkaTopics() {}
}
