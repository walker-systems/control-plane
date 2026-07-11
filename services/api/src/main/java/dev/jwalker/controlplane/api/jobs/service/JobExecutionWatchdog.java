package dev.jwalker.controlplane.api.jobs.service;

import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.service.AuditEventService;
import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobExecution;
import dev.jwalker.controlplane.api.jobs.model.JobExecutionStatus;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.repository.JobExecutionRepository;
import dev.jwalker.controlplane.api.jobs.repository.JobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Reclaims JobExecution rows whose lease has expired. A row lands here
// when the worker that started it either crashed, was killed, or was
// partitioned from the DB before it could commit a terminal state. The
// watchdog marks the execution TIMED_OUT and applies the same retry
// or DEAD_LETTER escalation the executor would have applied on a
// handler failure — attributing the failure to lease expiration.
@Slf4j
@Service
@RequiredArgsConstructor
public class JobExecutionWatchdog {

    private final JobExecutionRepository jobExecutionRepository;
    private final JobRepository jobRepository;
    private final AuditEventService auditEventService;
    private final Clock clock;

    @Value("${app.watchdog.batch-size:50}")
    int batchSize;

    // One tick, one transaction. Locks up to batchSize expired executions
    // under FOR UPDATE SKIP LOCKED so parallel watchdog invocations across
    // API instances reclaim disjoint slices without blocking each other.
    @Transactional
    public int reclaimExpired() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<JobExecution> expired = jobExecutionRepository.findExpiredForUpdate(
                now, PageRequest.of(0, batchSize));
        for (JobExecution exec : expired) {
            reclaimOne(exec, now);
        }
        return expired.size();
    }

    private void reclaimOne(JobExecution exec, OffsetDateTime now) {
        String errorMessage = "Lease expired at " + exec.getLeaseExpiresAt()
                + "; worker presumed dead";

        // Lock the parent Job before updating either row. Same lock-order
        // discipline as the executor's complete-phase (once the phase 8
        // executor refactor lands) to avoid deadlock.
        Optional<Job> jobOpt = jobRepository.findByIdWithRelationsForUpdate(exec.getJob().getId());
        if (jobOpt.isEmpty()) {
            log.warn(
                    "Reclaiming expired JobExecution {} but parent Job {} not found; skipping",
                    exec.getId(), exec.getJob().getId());
            return;
        }
        Job job = jobOpt.get();

        exec.setStatus(JobExecutionStatus.TIMED_OUT);
        exec.setFinishedAt(now);
        exec.setErrorMessage(errorMessage);

        int attemptNumber = exec.getAttemptNumber();

        // If the user requested cancel while the worker was hung, honor it:
        // the attempt is TIMED_OUT (that's what actually happened at the
        // worker layer), but the Job goes to CANCELLED instead of
        // retry/DEAD_LETTER. JOB_CANCELLED is the audit event; JOB_TIMED_OUT
        // is skipped since the user's intent supersedes the timeout.
        if (job.getCancelRequestedAt() != null) {
            job.setStatus(JobStatus.CANCELLED);
            job.touch();
            emitCancelled(job.getId(), attemptNumber, "TIMED_OUT");
            log.warn(
                    "Reclaimed expired JobExecution {} for Job {} (attempt {}): CANCELLED (user requested)",
                    exec.getId(), job.getId(), attemptNumber);
            return;
        }

        emitTimedOut(job, attemptNumber, errorMessage);

        if (attemptNumber <= job.getMaxRetries()) {
            Duration backoff = JobBackoffPolicy.computeBackoff(attemptNumber);
            job.setStatus(JobStatus.PENDING);
            job.setAvailableAt(now.plus(backoff));
            job.touch();
            log.warn(
                    "Reclaimed expired JobExecution {} for Job {} (attempt {}): re-queued with backoff {}",
                    exec.getId(), job.getId(), attemptNumber, backoff);
        } else {
            job.setStatus(JobStatus.DEAD_LETTER);
            job.touch();
            emitDeadLettered(job, attemptNumber, "lease_expired_max_retries_exceeded");
            log.error(
                    "Reclaimed expired JobExecution {} for Job {} (attempt {}): DEAD_LETTER (retries exhausted)",
                    exec.getId(), job.getId(), attemptNumber);
        }
    }

    private void emitTimedOut(Job job, int attemptNumber, String errorMessage) {
        auditEventService.recordWithActor(
                AuditEventType.JOB_TIMED_OUT, null, "Job", job.getId(),
                Map.of("attemptNumber", attemptNumber, "error", errorMessage));
    }

    private void emitDeadLettered(Job job, int finalAttempt, String reason) {
        auditEventService.recordWithActor(
                AuditEventType.JOB_DEAD_LETTERED, null, "Job", job.getId(),
                Map.of("finalAttempt", finalAttempt, "reason", reason));
    }

    private void emitCancelled(UUID jobId, int attemptNumber, String attemptOutcome) {
        auditEventService.recordWithActor(
                AuditEventType.JOB_CANCELLED, null, "Job", jobId,
                Map.of(
                        "previousStatus", "RUNNING",
                        "attemptNumber", attemptNumber,
                        "attemptOutcome", attemptOutcome));
    }
}
