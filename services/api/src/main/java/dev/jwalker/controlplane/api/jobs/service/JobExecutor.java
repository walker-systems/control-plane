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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Picks up PENDING jobs, transitions them through RUNNING → SUCCEEDED/FAILED,
// and records a JobExecution row per attempt. Fetches under FOR UPDATE
// SKIP LOCKED so parallel executor invocations can run without stepping
// on each other. In-process handlers run on the executor thread — fine
// for placeholders; when real long-running handlers arrive, either add
// a thread pool or move dispatch to a message broker.
//
// Retry logic and available_at backoff land in the next commit. Commit 1
// stops at "FAILED is terminal" so the state machine can be tested in
// isolation first.
@Slf4j
@Service
@RequiredArgsConstructor
public class JobExecutor {

    private final JobRepository jobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final AuditEventService auditEventService;
    private final JobHandlerRegistry handlerRegistry;
    private final Clock clock;

    @Value("${app.executor.batch-size:10}")
    int batchSize;

    @Transactional
    public int processPending() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Job> pending = jobRepository.findPendingForUpdate(now, PageRequest.of(0, batchSize));
        for (Job job : pending) {
            processOne(job, now);
        }
        return pending.size();
    }

    private void processOne(Job job, OffsetDateTime now) {
        int attemptNumber = countPastExecutions(job) + 1;
        Optional<JobHandler> handlerOpt = handlerRegistry.handlerFor(job.getType());

        if (handlerOpt.isEmpty()) {
            // Missing handler is a config bug — no amount of retrying will
            // help. Fail loud with a clear message and go straight to
            // DEAD_LETTER. Still write a JobExecution row so the paper
            // trail matches every other terminal state.
            deadLetterOnMissingHandler(job, attemptNumber, now);
            return;
        }

        JobExecution exec = beginExecution(job, attemptNumber, now);
        job.setStatus(JobStatus.RUNNING);
        job.touch();
        emitStarted(job, attemptNumber);

        try {
            String summary = handlerOpt.get().handle(job);
            exec.markSucceeded(summary);
            job.setStatus(JobStatus.SUCCEEDED);
            job.touch();
            emitSucceeded(job, attemptNumber, summary);
        } catch (Exception e) {
            // Catch anything the handler throws — we can't let the
            // exception propagate or it would roll back the entire batch's
            // transaction, undoing prior jobs' successful transitions.
            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            exec.markFailed(errorMessage);
            emitFailed(job, attemptNumber, errorMessage);

            // maxRetries is "retries after the first attempt," so with
            // maxRetries=3 attempts 1..3 retry and attempt 4 dead-letters.
            // Jobs with maxRetries=0 skip the retry branch entirely and go
            // straight to DEAD_LETTER on the first failure.
            if (attemptNumber <= job.getMaxRetries()) {
                Duration backoff = JobBackoffPolicy.computeBackoff(attemptNumber);
                job.setStatus(JobStatus.PENDING);
                job.setAvailableAt(now.plus(backoff));
                job.touch();
            } else {
                job.setStatus(JobStatus.DEAD_LETTER);
                job.touch();
                emitDeadLettered(job, attemptNumber, "max_retries_exceeded");
            }
        }
    }

    private int countPastExecutions(Job job) {
        return jobExecutionRepository.findByJob(job).size();
    }

    private JobExecution beginExecution(Job job, int attemptNumber, OffsetDateTime now) {
        JobExecution exec = new JobExecution(
                null, job, "in-process", attemptNumber, JobExecutionStatus.RUNNING);
        exec.setStartedAt(now);
        // Lease TTL. Not enforced by the executor yet; a lease-timeout
        // watchdog (reclaim stuck RUNNING jobs) is a future phase.
        exec.setLeaseExpiresAt(now.plusMinutes(5));
        return jobExecutionRepository.save(exec);
    }

    private void deadLetterOnMissingHandler(Job job, int attemptNumber, OffsetDateTime now) {
        String errorMessage = "No handler registered for type " + job.getType();
        JobExecution exec = new JobExecution(
                null, job, "in-process", attemptNumber, JobExecutionStatus.FAILED);
        exec.setStartedAt(now);
        exec.setFinishedAt(now);
        exec.setErrorMessage(errorMessage);
        jobExecutionRepository.save(exec);

        job.setStatus(JobStatus.DEAD_LETTER);
        job.touch();

        emitFailed(job, attemptNumber, errorMessage);
        emitDeadLettered(job, attemptNumber, "missing_handler");

        log.error("Job {} routed to DEAD_LETTER — no handler registered for type {}",
                job.getId(), job.getType());
    }

    private void emitStarted(Job job, int attemptNumber) {
        auditEventService.recordWithActor(
                AuditEventType.JOB_STARTED, null, "Job", job.getId(),
                Map.of("attemptNumber", attemptNumber));
    }

    private void emitSucceeded(Job job, int attemptNumber, String summary) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("attemptNumber", attemptNumber);
        if (summary != null) {
            meta.put("outputSummary", summary);
        }
        auditEventService.recordWithActor(
                AuditEventType.JOB_SUCCEEDED, null, "Job", job.getId(), meta);
    }

    private void emitFailed(Job job, int attemptNumber, String errorMessage) {
        auditEventService.recordWithActor(
                AuditEventType.JOB_FAILED, null, "Job", job.getId(),
                Map.of("attemptNumber", attemptNumber, "error", errorMessage));
    }

    private void emitDeadLettered(Job job, int finalAttempt, String reason) {
        auditEventService.recordWithActor(
                AuditEventType.JOB_DEAD_LETTERED, null, "Job", job.getId(),
                Map.of("finalAttempt", finalAttempt, "reason", reason));
    }
}
