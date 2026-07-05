package dev.jwalker.controlplane.api.jobs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Scheduled trigger that drains PENDING jobs. Same pattern as
// ScheduleTickJob: fixedDelay (not fixedRate) so a slow tick doesn't
// pile up parallel invocations on the same instance, try/catch around
// the whole call so one bad batch can't kill the loop, and a
// @ConditionalOnProperty gate so tests can disable the background loop
// and drive the executor directly.
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.executor.enabled", havingValue = "true", matchIfMissing = true)
public class JobExecutorTickJob {

    private final JobExecutor executor;

    @Scheduled(fixedDelayString = "${app.executor.tick-interval-ms:5000}")
    public void tick() {
        try {
            int processed = executor.processPending();
            if (processed > 0) {
                log.info("Executor tick processed {} jobs", processed);
            }
        } catch (RuntimeException e) {
            log.error("Executor tick failed", e);
        }
    }
}
