package com.comeback.researchplatform.agentservice.critic;

import com.comeback.researchplatform.common.ClaimsReady;
import com.comeback.researchplatform.common.KafkaTopics;
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

    @KafkaListener(topics = KafkaTopics.CLAIMS_READY)
    public void onClaimsReady(ClaimsReady claimsReady) {
        criticService.verify(claimsReady.runId());
    }
}
