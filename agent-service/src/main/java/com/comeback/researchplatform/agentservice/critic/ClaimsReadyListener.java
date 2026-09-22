package com.comeback.researchplatform.agentservice.critic;

import com.comeback.researchplatform.common.ClaimsReady;
import com.comeback.researchplatform.common.KafkaTopics;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Profile("CRITIC")
public class ClaimsReadyListener {

    private final CriticService criticService;

    public ClaimsReadyListener(CriticService criticService) {
        this.criticService = criticService;
    }

    // See ResearchSubtaskListener's comment: each listener needs its own
    // consumer group, not a shared global default.
    @KafkaListener(topics = KafkaTopics.CLAIMS_READY, groupId = "agent-service-critic")
    public void onClaimsReady(ClaimsReady claimsReady) {
        MDC.put("runId", claimsReady.runId().toString());
        try {
            criticService.verify(claimsReady.runId());
        } finally {
            MDC.remove("runId");
        }
    }
}
