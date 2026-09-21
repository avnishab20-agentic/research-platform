package com.comeback.researchplatform.agentservice.researcher;

import com.comeback.researchplatform.common.KafkaTopics;
import com.comeback.researchplatform.common.ResearchFinding;
import com.comeback.researchplatform.common.ResearchSubtask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumes one sub-question at a time (max-poll-records: 1, see
 * application.yml) and publishes the finished finding. concurrency: 6 means
 * up to 6 of these run in parallel across research.subtasks' 12 partitions --
 * PLAN's "6 researchers run in parallel" done-when for Week 2.
 */
@Component
public class ResearchSubtaskListener {

    private static final Logger log = LoggerFactory.getLogger(ResearchSubtaskListener.class);

    private final ResearcherService researcherService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public ResearchSubtaskListener(ResearcherService researcherService,
                                    KafkaTemplate<String, Object> kafkaTemplate) {
        this.researcherService = researcherService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = KafkaTopics.RESEARCH_SUBTASKS)
    public void onSubtask(ResearchSubtask subtask) {
        log.info("Researching sub-question '{}' (run {})", subtask.subQuestion(), subtask.runId());
        ResearchFinding finding = researcherService.research(subtask);
        // Keyed by runId so every finding for one run lands on the same
        // partition -- not required for fan-in correctness (that's a Postgres
        // counter, not partition ordering) but keeps one run's traffic
        // co-located, which is easier to reason about when debugging.
        kafkaTemplate.send(KafkaTopics.RESEARCH_FINDINGS, subtask.runId().toString(), finding);
    }
}
