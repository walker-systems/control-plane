package dev.jwalker.controlplane.api.jobs.service;

import dev.jwalker.controlplane.api.jobs.model.Job;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

// Demo-oriented base for the four placeholder JobHandlers. Real handlers
// will replace these one at a time; until they do, we want jobs to
// *look* like they're doing something so the UI (and any recruiter
// watching over a shoulder) sees:
//
//   PENDING → RUNNING (for several seconds, with progress logs)
//     → SUCCEEDED     (most of the time)
//     → FAILED → PENDING (retry, per JobExecutorTxOps.completeFailure)
//     → DEAD_LETTER    (after retries are exhausted — rare, but happens)
//
// Simulation is fully suppressed when chunk-millis is 0. In that mode
// handle() short-circuits to instant guaranteed success — no sleep,
// no failure roll. That keeps integration tests fast and deterministic
// without needing a second set of handler classes.
@Slf4j
public abstract class SimulatedWorkHandler implements JobHandler {

    @Value("${app.demo.simulated-handlers.chunk-millis:2000}")
    long chunkMillis;

    // How many chunks of work this handler simulates. Actual value is
    // picked uniformly in [minChunks, maxChunks] per invocation so the
    // demo doesn't feel metronomic.
    protected abstract int getMinChunks();
    protected abstract int getMaxChunks();

    // Probability (0.0 – 1.0) of throwing at the end of the simulated
    // run. Applied only when chunkMillis > 0.
    protected abstract double getFailureRate();

    // Type-flavored strings so the logs read like real work.
    protected abstract String getProgressLabel();
    protected abstract String getSuccessSummary(int chunksCompleted);

    @Override
    public String handle(Job job) throws Exception {
        int chunks = ThreadLocalRandom.current().nextInt(getMinChunks(), getMaxChunks() + 1);

        if (chunkMillis <= 0) {
            // Instant / test mode: deterministic success, no sleep, no fail roll.
            return getSuccessSummary(chunks);
        }

        log.info("[{}] Job {} starting — {} chunks (~{}s)",
                getSupportedType(), job.getId(), chunks, chunks * chunkMillis / 1000);

        for (int i = 1; i <= chunks; i++) {
            Thread.sleep(chunkMillis);
            log.info("[{}] Job {} — {} ({}/{})",
                    getSupportedType(), job.getId(), getProgressLabel(), i, chunks);
        }

        if (ThreadLocalRandom.current().nextDouble() < getFailureRate()) {
            String reason = getSupportedType() + " simulated failure at completion";
            log.warn("[{}] Job {} — {}", getSupportedType(), job.getId(), reason);
            throw new RuntimeException(reason);
        }

        return getSuccessSummary(chunks);
    }
}
