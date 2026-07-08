package dev.jwalker.controlplane.api.jobs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Scheduled trigger for the JobExecutionWatchdog. Same shape as
// ScheduleTickJob and JobExecutorTickJob: fixedDelay so a slow tick
// doesn't queue parallel invocations, try/catch so one bad batch
// can't stall the loop, and a @ConditionalOnProperty gate so tests
// disable the background scan and drive the watchdog directly.
//
// Default cadence is 60s — lease TTL is 5min, so a stuck execution
// gets reclaimed within ~6min at worst. Faster ticks add DB load
// without meaningfully improving that.
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.watchdog.enabled", havingValue = "true", matchIfMissing = true)
public class WatchdogTickJob {

    private final JobExecutionWatchdog watchdog;

    @Scheduled(fixedDelayString = "${app.watchdog.tick-interval-ms:60000}")
    public void tick() {
        try {
            int reclaimed = watchdog.reclaimExpired();
            if (reclaimed > 0) {
                log.info("Watchdog tick reclaimed {} timed-out executions", reclaimed);
            }
        } catch (RuntimeException e) {
            log.error("Watchdog tick failed", e);
        }
    }
}
