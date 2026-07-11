package dev.jwalker.controlplane.api.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.service.AuditEventService;
import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobExecution;
import dev.jwalker.controlplane.api.jobs.model.JobExecutionStatus;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.jobs.repository.JobExecutionRepository;
import dev.jwalker.controlplane.api.jobs.repository.JobRepository;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

// Exercises the state machine: what pick / complete methods do to the
// Job and JobExecution rows and which audit events they fire. The
// orchestration layer above (JobExecutor) is covered by JobExecutorTest.
@ExtendWith(MockitoExtension.class)
class JobExecutorTxOpsTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-08T12:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

    @Mock private JobRepository jobRepository;
    @Mock private JobExecutionRepository jobExecutionRepository;
    @Mock private AuditEventService auditEventService;

    @InjectMocks
    private JobExecutorTxOps txOps;

    @BeforeEach
    void ensureFreshMocks() {
        // No shared setup — each test stubs exactly what it needs.
    }

    // --- pickBatch -----------------------------------------------------

    @Test
    void pickBatch_transitionsJobsToRunning_createsExecutions_andEmitsStarted() {
        Job job = pendingJob(UUID.randomUUID());
        when(jobRepository.findPendingForUpdate(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(job));
        when(jobExecutionRepository.countByJob_Id(job.getId())).thenReturn(0L);
        when(jobExecutionRepository.save(any(JobExecution.class))).thenAnswer(inv -> {
            JobExecution e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });

        List<PickedJob> picked = txOps.pickBatch(NOW, 10);

        assertThat(picked).hasSize(1);
        assertThat(picked.get(0).job()).isSameAs(job);
        assertThat(picked.get(0).attemptNumber()).isEqualTo(1);
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);

        ArgumentCaptor<JobExecution> execCap = ArgumentCaptor.forClass(JobExecution.class);
        verify(jobExecutionRepository).save(execCap.capture());
        JobExecution savedExec = execCap.getValue();
        assertThat(savedExec.getStatus()).isEqualTo(JobExecutionStatus.RUNNING);
        assertThat(savedExec.getAttemptNumber()).isEqualTo(1);
        assertThat(savedExec.getStartedAt()).isEqualTo(NOW);
        assertThat(savedExec.getLeaseExpiresAt()).isEqualTo(NOW.plusMinutes(5));

        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_STARTED), isNull(), eq("Job"), eq(job.getId()), any());
    }

    @Test
    void pickBatch_incrementsAttemptNumberBasedOnPastExecutions() {
        Job job = pendingJob(UUID.randomUUID());
        when(jobRepository.findPendingForUpdate(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(job));
        when(jobExecutionRepository.countByJob_Id(job.getId())).thenReturn(2L);
        when(jobExecutionRepository.save(any(JobExecution.class))).thenAnswer(inv -> inv.getArgument(0));

        List<PickedJob> picked = txOps.pickBatch(NOW, 10);

        assertThat(picked.get(0).attemptNumber()).isEqualTo(3);
    }

    @Test
    void pickBatch_returnsEmpty_whenNothingPending() {
        when(jobRepository.findPendingForUpdate(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

        List<PickedJob> picked = txOps.pickBatch(NOW, 10);

        assertThat(picked).isEmpty();
        verify(jobExecutionRepository, never()).save(any(JobExecution.class));
        verify(auditEventService, never()).recordWithActor(any(), any(), any(), any(), any());
    }

    // --- completeSuccess -----------------------------------------------

    @Test
    void completeSuccess_transitionsJobAndExecution_andEmitsSucceeded() {
        Job job = runningJob(UUID.randomUUID(), 3);
        JobExecution exec = runningExecution(job, 1);
        when(jobRepository.findByIdWithRelationsForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(jobExecutionRepository.findByIdForUpdate(exec.getId())).thenReturn(Optional.of(exec));

        txOps.completeSuccess(job.getId(), exec.getId(), 1, "done");

        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.SUCCEEDED);
        assertThat(exec.getOutputSummary()).isEqualTo("done");

        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_SUCCEEDED), isNull(), eq("Job"), eq(job.getId()), any());
    }

    // --- completeFailure -----------------------------------------------

    @Test
    void completeFailure_retriesJobWithBackoff_whenAttemptsRemain() {
        Job job = runningJob(UUID.randomUUID(), 3);
        JobExecution exec = runningExecution(job, 1);
        when(jobRepository.findByIdWithRelationsForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(jobExecutionRepository.findByIdForUpdate(exec.getId())).thenReturn(Optional.of(exec));

        txOps.completeFailure(job.getId(), exec.getId(), 1, "boom", NOW);

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.FAILED);
        assertThat(exec.getErrorMessage()).isEqualTo("boom");
        // Attempt 1 → backoff 20s (10 * 2^1).
        assertThat(job.getAvailableAt()).isEqualTo(NOW.plus(Duration.ofSeconds(20)));

        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_FAILED), isNull(), eq("Job"), eq(job.getId()), any());
        verify(auditEventService, never()).recordWithActor(
                eq(AuditEventType.JOB_DEAD_LETTERED), any(), any(), any(), any());
    }

    @Test
    void completeFailure_deadLettersJob_whenRetriesExhausted() {
        // attemptNumber 4 > maxRetries 3 → DEAD_LETTER path
        Job job = runningJob(UUID.randomUUID(), 3);
        JobExecution exec = runningExecution(job, 4);
        when(jobRepository.findByIdWithRelationsForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(jobExecutionRepository.findByIdForUpdate(exec.getId())).thenReturn(Optional.of(exec));

        txOps.completeFailure(job.getId(), exec.getId(), 4, "boom", NOW);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD_LETTER);
        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.FAILED);

        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_FAILED), isNull(), eq("Job"), eq(job.getId()), any());
        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_DEAD_LETTERED), isNull(), eq("Job"), eq(job.getId()), any());
    }

    // --- cancel honoring -----------------------------------------------

    @Test
    void completeSuccess_honorsCancelRequest_transitionsJobToCancelledAndEmitsCancelled() {
        Job job = runningJob(UUID.randomUUID(), 3);
        job.setCancelRequestedAt(NOW.minusSeconds(2));
        JobExecution exec = runningExecution(job, 1);
        when(jobRepository.findByIdWithRelationsForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(jobExecutionRepository.findByIdForUpdate(exec.getId())).thenReturn(Optional.of(exec));

        txOps.completeSuccess(job.getId(), exec.getId(), 1, "done");

        // Job: CANCELLED; JobExecution: SUCCEEDED (attempt outcome preserved).
        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.SUCCEEDED);
        assertThat(exec.getOutputSummary()).isEqualTo("done");

        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_CANCELLED), isNull(), eq("Job"), eq(job.getId()), any());
        verify(auditEventService, never()).recordWithActor(
                eq(AuditEventType.JOB_SUCCEEDED), any(), any(), any(), any());
    }

    @Test
    void completeFailure_honorsCancelRequest_skipsRetryAndDeadLetter() {
        Job job = runningJob(UUID.randomUUID(), 3);
        job.setCancelRequestedAt(NOW.minusSeconds(2));
        JobExecution exec = runningExecution(job, 1);
        when(jobRepository.findByIdWithRelationsForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(jobExecutionRepository.findByIdForUpdate(exec.getId())).thenReturn(Optional.of(exec));

        txOps.completeFailure(job.getId(), exec.getId(), 1, "boom", NOW);

        // Job: CANCELLED (no retry despite attempts remaining).
        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.FAILED);

        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_CANCELLED), isNull(), eq("Job"), eq(job.getId()), any());
        verify(auditEventService, never()).recordWithActor(
                eq(AuditEventType.JOB_FAILED), any(), any(), any(), any());
        verify(auditEventService, never()).recordWithActor(
                eq(AuditEventType.JOB_DEAD_LETTERED), any(), any(), any(), any());
    }

    @Test
    void completeMissingHandler_honorsCancelRequest_transitionsToCancelledInsteadOfDeadLetter() {
        Job job = runningJob(UUID.randomUUID(), 3);
        job.setCancelRequestedAt(NOW.minusSeconds(2));
        JobExecution exec = runningExecution(job, 1);
        when(jobRepository.findByIdWithRelationsForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(jobExecutionRepository.findByIdForUpdate(exec.getId())).thenReturn(Optional.of(exec));

        txOps.completeMissingHandler(job.getId(), exec.getId(), JobType.CRM_SYNC, 1);

        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.FAILED);

        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_CANCELLED), isNull(), eq("Job"), eq(job.getId()), any());
        verify(auditEventService, never()).recordWithActor(
                eq(AuditEventType.JOB_DEAD_LETTERED), any(), any(), any(), any());
    }

    // --- already-finalized bailout -------------------------------------
    //
    // Race: handler ran past its 5-min lease, watchdog reclaimed the
    // JobExecution to TIMED_OUT, then the handler finally returned and
    // the executor tries to finalize. The complete methods must be
    // no-ops — otherwise we overwrite the watchdog's outcome and (worse)
    // stomp on the parent Job while attempt N+1 may already be running.

    @Test
    void completeSuccess_isNoOp_whenExecutionAlreadyTimedOut() {
        Job job = runningJob(UUID.randomUUID(), 3);
        JobExecution exec = runningExecution(job, 1);
        exec.setStatus(JobExecutionStatus.TIMED_OUT);
        when(jobExecutionRepository.findByIdForUpdate(exec.getId())).thenReturn(Optional.of(exec));

        txOps.completeSuccess(job.getId(), exec.getId(), 1, "done");

        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.TIMED_OUT);
        // Job never even loaded — Job lock was never acquired.
        verify(jobRepository, never()).findByIdWithRelationsForUpdate(any());
        verify(auditEventService, never()).recordWithActor(any(), any(), any(), any(), any());
    }

    @Test
    void completeFailure_isNoOp_whenExecutionAlreadyTimedOut() {
        Job job = runningJob(UUID.randomUUID(), 3);
        JobExecution exec = runningExecution(job, 1);
        exec.setStatus(JobExecutionStatus.TIMED_OUT);
        when(jobExecutionRepository.findByIdForUpdate(exec.getId())).thenReturn(Optional.of(exec));

        txOps.completeFailure(job.getId(), exec.getId(), 1, "boom", NOW);

        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.TIMED_OUT);
        verify(jobRepository, never()).findByIdWithRelationsForUpdate(any());
        verify(auditEventService, never()).recordWithActor(any(), any(), any(), any(), any());
    }

    @Test
    void completeMissingHandler_isNoOp_whenExecutionAlreadyTimedOut() {
        Job job = runningJob(UUID.randomUUID(), 3);
        JobExecution exec = runningExecution(job, 1);
        exec.setStatus(JobExecutionStatus.TIMED_OUT);
        when(jobExecutionRepository.findByIdForUpdate(exec.getId())).thenReturn(Optional.of(exec));

        txOps.completeMissingHandler(job.getId(), exec.getId(), JobType.CRM_SYNC, 1);

        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.TIMED_OUT);
        verify(jobRepository, never()).findByIdWithRelationsForUpdate(any());
        verify(auditEventService, never()).recordWithActor(any(), any(), any(), any(), any());
    }

    // --- completeMissingHandler ----------------------------------------

    @Test
    void completeMissingHandler_deadLettersImmediately_andEmitsBothAudits() {
        Job job = runningJob(UUID.randomUUID(), 3);
        JobExecution exec = runningExecution(job, 1);
        when(jobRepository.findByIdWithRelationsForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(jobExecutionRepository.findByIdForUpdate(exec.getId())).thenReturn(Optional.of(exec));

        txOps.completeMissingHandler(job.getId(), exec.getId(), JobType.CRM_SYNC, 1);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD_LETTER);
        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.FAILED);
        assertThat(exec.getErrorMessage()).contains("No handler registered");

        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_FAILED), isNull(), eq("Job"), eq(job.getId()), any());
        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_DEAD_LETTERED), isNull(), eq("Job"), eq(job.getId()), any());
    }

    // --- helpers -------------------------------------------------------

    private Job pendingJob(UUID id) {
        User owner = new User(UUID.randomUUID(), "owner@example.com", "hash", UserStatus.ACTIVE);
        return new Job(id, owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.PENDING, JobPriority.MEDIUM, null, 3);
    }

    private Job runningJob(UUID id, int maxRetries) {
        User owner = new User(UUID.randomUUID(), "owner@example.com", "hash", UserStatus.ACTIVE);
        return new Job(id, owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.RUNNING, JobPriority.MEDIUM, null, maxRetries);
    }

    private JobExecution runningExecution(Job job, int attemptNumber) {
        JobExecution exec = new JobExecution(
                UUID.randomUUID(), job, "in-process", attemptNumber, JobExecutionStatus.RUNNING);
        exec.setStartedAt(NOW.minusMinutes(1));
        exec.setLeaseExpiresAt(NOW.plusMinutes(4));
        return exec;
    }
}
