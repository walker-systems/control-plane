package dev.jwalker.controlplane.api.jobs.service;

import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.service.AuditEventService;
import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobExecution;
import dev.jwalker.controlplane.api.jobs.model.JobExecutionStatus;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.jobs.repository.JobExecutionRepository;
import dev.jwalker.controlplane.api.jobs.repository.JobRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Transactional operations backing JobExecutor's pick / run / complete
// pipeline. Each public method owns its own short transaction so the
// executor can call handler.handle(...) between them without holding
// any DB locks. Splitting this out into its own bean is what makes the
// tx boundaries fire — @Transactional only applies across bean calls,
// not on self-invocation within the same class.
@Slf4j
@Service
@RequiredArgsConstructor
public class JobExecutorTxOps {

    private final JobRepository jobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final AuditEventService auditEventService;

    // Pick a batch of PENDING jobs, transition each to RUNNING with a fresh
    // JobExecution row, emit JOB_STARTED, and commit. The returned Jobs are
    // detached — the caller can invoke handlers on them outside any
    // transaction, and the DB row locks are released the moment this
    // method returns.
    @Transactional
    public List<PickedJob> pickBatch(OffsetDateTime now, int batchSize) {
        List<Job> jobs = jobRepository.findPendingForUpdate(now, PageRequest.of(0, batchSize));
        List<PickedJob> picked = new ArrayList<>(jobs.size());
        for (Job job : jobs) {
            int attemptNumber = (int) jobExecutionRepository.countByJob_Id(job.getId()) + 1;
            JobExecution exec = new JobExecution(
                    null, job, "in-process", attemptNumber, JobExecutionStatus.RUNNING);
            exec.setStartedAt(now);
            // Lease TTL — watchdog reclaims RUNNING executions past this.
            exec.setLeaseExpiresAt(now.plusMinutes(5));
            JobExecution savedExec = jobExecutionRepository.save(exec);

            job.setStatus(JobStatus.RUNNING);
            job.touch();

            emitStarted(job.getId(), attemptNumber);

            picked.add(new PickedJob(job, savedExec.getId(), attemptNumber));
        }
        return picked;
    }

    // Handler returned. Lock the JobExecution first (matching the
    // watchdog's exec-then-job order to avoid deadlock), then re-lock
    // the Job. If the execution is no longer RUNNING, the watchdog
    // already reclaimed this attempt while the handler was running —
    // its outcome and audit already fired, and a follow-up attempt may
    // already be in flight against the same Job. Bail out without
    // touching either row. Otherwise mark the JobExecution SUCCEEDED
    // (the attempt's actual outcome — preserved regardless of cancel)
    // and transition the Job. If cancel was requested during handler
    // execution, the Job goes to CANCELLED and JOB_CANCELLED fires with
    // attemptOutcome=SUCCEEDED. Otherwise JOB_SUCCEEDED fires as usual.
    @Transactional
    public void completeSuccess(UUID jobId, UUID execId, int attemptNumber, String summary) {
        JobExecution exec = jobExecutionRepository.findByIdForUpdate(execId).orElseThrow(
                () -> new IllegalStateException("JobExecution " + execId + " missing at completion"));
        if (exec.getStatus() != JobExecutionStatus.RUNNING) {
            log.info("Skipping completeSuccess for exec {} (job {}) — status is {}, "
                    + "attempt already finalized (likely by watchdog)",
                    execId, jobId, exec.getStatus());
            return;
        }
        Job job = jobRepository.findByIdWithRelationsForUpdate(jobId).orElseThrow(
                () -> new IllegalStateException("Job " + jobId + " missing at completion"));

        exec.markSucceeded(summary);

        if (job.getCancelRequestedAt() != null) {
            job.setStatus(JobStatus.CANCELLED);
            job.touch();
            emitCancelled(jobId, attemptNumber, "RUNNING", "SUCCEEDED");
        } else {
            job.setStatus(JobStatus.SUCCEEDED);
            job.touch();
            emitSucceeded(jobId, attemptNumber, summary);
        }
    }

    // Handler threw. Re-lock the Job, mark the JobExecution FAILED (the
    // attempt's actual outcome), then decide the Job's fate:
    //  - cancel_requested_at set → straight to CANCELLED, no retry.
    //    JOB_CANCELLED fires with attemptOutcome=FAILED.
    //  - retries remain → PENDING with backoff; JOB_FAILED fires.
    //  - retries exhausted → DEAD_LETTER; JOB_FAILED + JOB_DEAD_LETTERED
    //    fire.
    @Transactional
    public void completeFailure(
            UUID jobId, UUID execId, int attemptNumber, String errorMessage, OffsetDateTime now) {
        JobExecution exec = jobExecutionRepository.findByIdForUpdate(execId).orElseThrow(
                () -> new IllegalStateException("JobExecution " + execId + " missing at completion"));
        if (exec.getStatus() != JobExecutionStatus.RUNNING) {
            log.info("Skipping completeFailure for exec {} (job {}) — status is {}, "
                    + "attempt already finalized (likely by watchdog)",
                    execId, jobId, exec.getStatus());
            return;
        }
        Job job = jobRepository.findByIdWithRelationsForUpdate(jobId).orElseThrow(
                () -> new IllegalStateException("Job " + jobId + " missing at completion"));

        exec.markFailed(errorMessage);

        if (job.getCancelRequestedAt() != null) {
            job.setStatus(JobStatus.CANCELLED);
            job.touch();
            emitCancelled(jobId, attemptNumber, "RUNNING", "FAILED");
            return;
        }

        emitFailed(jobId, attemptNumber, errorMessage);

        // maxRetries is "retries after the first attempt," so with
        // maxRetries=3 attempts 1..3 retry and attempt 4 dead-letters.
        if (attemptNumber <= job.getMaxRetries()) {
            Duration backoff = JobBackoffPolicy.computeBackoff(attemptNumber);
            job.setStatus(JobStatus.PENDING);
            job.setAvailableAt(now.plus(backoff));
            job.touch();
        } else {
            job.setStatus(JobStatus.DEAD_LETTER);
            job.touch();
            emitDeadLettered(jobId, attemptNumber, "max_retries_exceeded");
        }
    }

    // Handler was never invoked because no @Component JobHandler matched
    // the type. Straight to DEAD_LETTER (retrying a config bug can't
    // help) unless the user requested cancel first — in which case cancel
    // wins and the Job goes to CANCELLED. Either way we record the
    // JobExecution as FAILED with a diagnostic message.
    @Transactional
    public void completeMissingHandler(
            UUID jobId, UUID execId, JobType type, int attemptNumber) {
        JobExecution exec = jobExecutionRepository.findByIdForUpdate(execId).orElseThrow(
                () -> new IllegalStateException("JobExecution " + execId + " missing at completion"));
        if (exec.getStatus() != JobExecutionStatus.RUNNING) {
            log.info("Skipping completeMissingHandler for exec {} (job {}) — status is {}, "
                    + "attempt already finalized (likely by watchdog)",
                    execId, jobId, exec.getStatus());
            return;
        }
        Job job = jobRepository.findByIdWithRelationsForUpdate(jobId).orElseThrow(
                () -> new IllegalStateException("Job " + jobId + " missing at completion"));

        String errorMessage = "No handler registered for type " + type;
        exec.markFailed(errorMessage);

        if (job.getCancelRequestedAt() != null) {
            job.setStatus(JobStatus.CANCELLED);
            job.touch();
            emitCancelled(jobId, attemptNumber, "RUNNING", "FAILED_MISSING_HANDLER");
            return;
        }

        job.setStatus(JobStatus.DEAD_LETTER);
        job.touch();
        emitFailed(jobId, attemptNumber, errorMessage);
        emitDeadLettered(jobId, attemptNumber, "missing_handler");

        log.error("Job {} routed to DEAD_LETTER — no handler registered for type {}",
                jobId, type);
    }

    private void emitStarted(UUID jobId, int attemptNumber) {
        auditEventService.recordWithActor(
                AuditEventType.JOB_STARTED, null, "Job", jobId,
                Map.of("attemptNumber", attemptNumber));
    }

    private void emitSucceeded(UUID jobId, int attemptNumber, String summary) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("attemptNumber", attemptNumber);
        if (summary != null) {
            meta.put("outputSummary", summary);
        }
        auditEventService.recordWithActor(
                AuditEventType.JOB_SUCCEEDED, null, "Job", jobId, meta);
    }

    private void emitFailed(UUID jobId, int attemptNumber, String errorMessage) {
        auditEventService.recordWithActor(
                AuditEventType.JOB_FAILED, null, "Job", jobId,
                Map.of("attemptNumber", attemptNumber, "error", errorMessage));
    }

    private void emitDeadLettered(UUID jobId, int finalAttempt, String reason) {
        auditEventService.recordWithActor(
                AuditEventType.JOB_DEAD_LETTERED, null, "Job", jobId,
                Map.of("finalAttempt", finalAttempt, "reason", reason));
    }

    private void emitCancelled(
            UUID jobId, int attemptNumber, String previousStatus, String attemptOutcome) {
        auditEventService.recordWithActor(
                AuditEventType.JOB_CANCELLED, null, "Job", jobId,
                Map.of(
                        "previousStatus", previousStatus,
                        "attemptNumber", attemptNumber,
                        "attemptOutcome", attemptOutcome));
    }
}
