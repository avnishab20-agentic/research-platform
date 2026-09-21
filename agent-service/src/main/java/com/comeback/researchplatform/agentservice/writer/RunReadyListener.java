package com.comeback.researchplatform.agentservice.writer;

import com.comeback.researchplatform.common.KafkaTopics;
import com.comeback.researchplatform.common.RunReady;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Profile("WRITER")
public class RunReadyListener {

    private final WriterService writerService;

    public RunReadyListener(WriterService writerService) {
        this.writerService = writerService;
    }

    @KafkaListener(topics = KafkaTopics.RUN_READY)
    public void onRunReady(RunReady runReady) {
        writerService.write(runReady.runId());
    }
}
