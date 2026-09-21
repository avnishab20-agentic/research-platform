package com.comeback.researchplatform.controlplane.fanin;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs every 15s per PLAN's "Sweeper every 15s" -- see FanInService for
 *  what "released" actually does. */
@Component
public class DeadlineSweeper {

    private final FanInService fanInService;

    public DeadlineSweeper(FanInService fanInService) {
        this.fanInService = fanInService;
    }

    @Scheduled(fixedDelay = 15000)
    public void sweep() {
        fanInService.releaseExpiredLevels();
    }
}
