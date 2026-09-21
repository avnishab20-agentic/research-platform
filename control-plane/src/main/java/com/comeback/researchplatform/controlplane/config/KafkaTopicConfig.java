package com.comeback.researchplatform.controlplane.config;

import com.comeback.researchplatform.common.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the three topics as Spring beans so a fresh {@code docker compose up}
 * gets a working broker without a manual {@code rpk topic create} step -- Redpanda
 * has no persisted volume in this project, so anything created by hand is gone on
 * the next restart. {@code control-plane} owns provisioning because it's the
 * orchestrator; {@code agent-service} only ever produces/consumes against these
 * same names (see {@link KafkaTopics}).
 * <p>
 * Partition counts match the Week 1 plan: research.subtasks is the widest because
 * up to 8 researchers fan out onto it at once; findings and events see less
 * concurrent traffic.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic researchSubtasks() {
        return TopicBuilder.name(KafkaTopics.RESEARCH_SUBTASKS).partitions(12).replicas(1).build();
    }

    @Bean
    public NewTopic researchFindings() {
        return TopicBuilder.name(KafkaTopics.RESEARCH_FINDINGS).partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic agentEvents() {
        return TopicBuilder.name(KafkaTopics.AGENT_EVENTS).partitions(3).replicas(1).build();
    }

    // Week 3: one message per completed run, not per sub-question -- low
    // traffic, 3 partitions is plenty.
    @Bean
    public NewTopic runReady() {
        return TopicBuilder.name(KafkaTopics.RUN_READY).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic claimsReady() {
        return TopicBuilder.name(KafkaTopics.CLAIMS_READY).partitions(3).replicas(1).build();
    }
}
