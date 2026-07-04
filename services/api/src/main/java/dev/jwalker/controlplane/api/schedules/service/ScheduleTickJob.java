package dev.jwalker.controlplane.api.schedules.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduleTickJob {

    private final ScheduleMaterializer materializer;

    // fixedDelayString (not fixedRateString) — Spring waits for the previous
    // tick to complete before scheduling the next. If a tick runs long, we
    // don't queue up parallel invocations on the same instance. The whole
    // method is wrapped in a try/catch so one bad tick can't stall the loop.
    @Scheduled(fixedDelayString = "${app.scheduling.tick-interval-ms:30000}")
    public void tick() {
        try {
            int fired = materializer.materializeDue();
            if (fired > 0) {
                log.info("Schedule tick materialized {} jobs", fired);
            }
        } catch (RuntimeException e) {
            log.error("Schedule tick failed", e);
        }
    }
}
