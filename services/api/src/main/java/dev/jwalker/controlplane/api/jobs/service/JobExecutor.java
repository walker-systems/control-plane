package dev.jwalker.controlplane.api.jobs.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Orchestrator over the pick / run / complete pipeline. Not @Transactional
// itself — each stage runs in its own short transaction inside
// JobExecutorTxOps, which is what lets the handler run without holding
// any DB locks. Batch layout:
//
//   pickBatch()          — tx: lock N PENDING jobs, transition to RUNNING, commit.
//                          Row locks release here.
//   for each picked job:
//     startAttempt()      — tx: renew this job's lease, verify still RUNNING.
//                          Skips the rest if the watchdog beat us to it.
//     handler.handle(job) — no tx. The slow part. Cancel and watchdog can
//                          progress against other rows during this window.
//     completeSuccess()   — tx: re-lock, mark SUCCEEDED, commit.
//     completeFailure()   — tx: re-lock, mark FAILED and retry or DEAD_LETTER,
//                          commit.
//
// If the process dies between pickBatch's commit and completion, the Job
// stays RUNNING with a valid lease. The watchdog reclaims it when the
// lease expires.
@Slf4j
@Service
@RequiredArgsConstructor
public class JobExecutor {

    private final JobExecutorTxOps txOps;
    private final JobHandlerRegistry handlerRegistry;
    private final Clock clock;

    @Value("${app.executor.batch-size:10}")
    int batchSize;

    public int processPending() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<PickedJob> picked = txOps.pickBatch(now, batchSize);
        for (PickedJob p : picked) {
            runOne(p);
        }
        return picked.size();
    }

    private void runOne(PickedJob picked) {
        // Renew this attempt's lease right before running its handler.
        // If the watchdog reclaimed it while earlier jobs in the batch
        // were running, skip the handler entirely — attempt N+1 for the
        // same Job may already be in flight on another executor tick.
        if (!txOps.startAttempt(picked.execId(), OffsetDateTime.now(clock))) {
            log.info("Skipping handler for exec {} (job {}) — attempt no longer RUNNING, "
                    + "likely reclaimed by watchdog while earlier batch jobs were running",
                    picked.execId(), picked.job().getId());
            return;
        }

        Optional<JobHandler> handlerOpt = handlerRegistry.handlerFor(picked.job().getType());
        if (handlerOpt.isEmpty()) {
            txOps.completeMissingHandler(
                    picked.job().getId(), picked.execId(),
                    picked.job().getType(), picked.attemptNumber());
            return;
        }

        try {
            String summary = handlerOpt.get().handle(picked.job());
            txOps.completeSuccess(
                    picked.job().getId(), picked.execId(),
                    picked.attemptNumber(), summary);
        } catch (Exception e) {
            // Any handler exception → failure path. Catch here so one bad
            // job doesn't stall the rest of the batch.
            String errorMessage = e.getMessage() != null
                    ? e.getMessage()
                    : e.getClass().getSimpleName();
            txOps.completeFailure(
                    picked.job().getId(), picked.execId(),
                    picked.attemptNumber(), errorMessage,
                    OffsetDateTime.now(clock));
        }
    }
}
