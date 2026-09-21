package com.comeback.researchplatform.controlplane.fanin;

import com.comeback.researchplatform.common.KafkaTopics;
import com.comeback.researchplatform.common.ResearchFinding;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FindingListener {

    private final FanInService fanInService;

    public FindingListener(FanInService fanInService) {
        this.fanInService = fanInService;
    }

    @KafkaListener(topics = KafkaTopics.RESEARCH_FINDINGS)
    public void onFinding(ResearchFinding finding) {
        fanInService.recordFinding(finding);
    }
}
