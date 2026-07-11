package dev.jwalker.controlplane.api.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class JobExecutionWatchdogTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-08T12:00:00Z");

    @Mock private JobExecutionRepository jobExecutionRepository;
    @Mock private JobRepository jobRepository;
    @Mock private AuditEventService auditEventService;

    private final Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private JobExecutionWatchdog watchdog;

    @BeforeEach
    void setUp() {
        watchdog = new JobExecutionWatchdog(
                jobExecutionRepository, jobRepository, auditEventService, fixedClock);
        ReflectionTestUtils.setField(watchdog, "batchSize", 50);
    }

    @Test
    void reclaimExpired_marksExecutionTimedOutAndRequeuesJob_whenRetriesRemain() {
        Job job = pendingJob(3);
        JobExecution exec = expiredExecution(job, 1);
        stubExpired(List.of(exec));
        when(jobRepository.findByIdWithRelationsForUpdate(job.getId())).thenReturn(Optional.of(job));

        int reclaimed = watchdog.reclaimExpired();

        assertThat(reclaimed).isEqualTo(1);
        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.TIMED_OUT);
        assertThat(exec.getFinishedAt())
                .isEqualTo(OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
        assertThat(exec.getErrorMessage()).contains("Lease expired");

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
        assertThat(job.getAvailableAt()).isAfter(now);

        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_TIMED_OUT), isNull(), eq("Job"), eq(job.getId()), any());
        verify(auditEventService, never()).recordWithActor(
                eq(AuditEventType.JOB_DEAD_LETTERED), any(), any(), any(), any());
    }

    @Test
    void reclaimExpired_deadLettersJob_whenRetriesExhausted() {
        Job job = pendingJob(3);
        // attemptNumber 4 > maxRetries 3 → DEAD_LETTER path
        JobExecution exec = expiredExecution(job, 4);
        stubExpired(List.of(exec));
        when(jobRepository.findByIdWithRelationsForUpdate(job.getId())).thenReturn(Optional.of(job));

        watchdog.reclaimExpired();

        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.TIMED_OUT);
        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD_LETTER);

        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_TIMED_OUT), isNull(), eq("Job"), eq(job.getId()), any());
        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_DEAD_LETTERED), isNull(), eq("Job"), eq(job.getId()), any());
    }

    @Test
    void reclaimExpired_skipsExecution_whenParentJobMissing() {
        Job job = pendingJob(3);
        JobExecution exec = expiredExecution(job, 1);
        stubExpired(List.of(exec));
        when(jobRepository.findByIdWithRelationsForUpdate(job.getId())).thenReturn(Optional.empty());

        int reclaimed = watchdog.reclaimExpired();

        // The row is still counted as picked up, but no audit or state changes.
        assertThat(reclaimed).isEqualTo(1);
        verify(auditEventService, never()).recordWithActor(any(), any(), any(), any(), any());
    }

    @Test
    void reclaimExpired_processesMultipleExecutionsInOneTick() {
        Job jobA = pendingJob(3);
        Job jobB = pendingJob(3);
        JobExecution execA = expiredExecution(jobA, 1);
        JobExecution execB = expiredExecution(jobB, 1);
        stubExpired(List.of(execA, execB));
        when(jobRepository.findByIdWithRelationsForUpdate(jobA.getId())).thenReturn(Optional.of(jobA));
        when(jobRepository.findByIdWithRelationsForUpdate(jobB.getId())).thenReturn(Optional.of(jobB));

        int reclaimed = watchdog.reclaimExpired();

        assertThat(reclaimed).isEqualTo(2);
        assertThat(jobA.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(jobB.getStatus()).isEqualTo(JobStatus.PENDING);
        verify(auditEventService, times(2)).recordWithActor(
                eq(AuditEventType.JOB_TIMED_OUT), isNull(), eq("Job"), any(), any());
    }

    @Test
    void reclaimExpired_honorsCancelRequest_transitionsToCancelledInsteadOfRetry() {
        Job job = pendingJob(3);
        job.setCancelRequestedAt(OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).minusSeconds(2));
        JobExecution exec = expiredExecution(job, 1);
        stubExpired(List.of(exec));
        when(jobRepository.findByIdWithRelationsForUpdate(job.getId())).thenReturn(Optional.of(job));

        watchdog.reclaimExpired();

        // Job: CANCELLED (user's intent wins over retry). Execution: TIMED_OUT
        // (that's what actually happened at the worker layer).
        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.TIMED_OUT);

        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_CANCELLED), isNull(), eq("Job"), eq(job.getId()), any());
        verify(auditEventService, never()).recordWithActor(
                eq(AuditEventType.JOB_TIMED_OUT), any(), any(), any(), any());
        verify(auditEventService, never()).recordWithActor(
                eq(AuditEventType.JOB_DEAD_LETTERED), any(), any(), any(), any());
    }

    @Test
    void reclaimExpired_returnsZero_whenNothingExpired() {
        stubExpired(List.of());

        int reclaimed = watchdog.reclaimExpired();

        assertThat(reclaimed).isZero();
        verify(jobRepository, never()).findByIdWithRelationsForUpdate(any());
        verify(auditEventService, never()).recordWithActor(any(), any(), any(), any(), any());
    }

    // --- helpers ---

    private Job pendingJob(int maxRetries) {
        User owner = new User(UUID.randomUUID(), "owner@example.com", "hash", UserStatus.ACTIVE);
        return new Job(
                UUID.randomUUID(), owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.RUNNING, JobPriority.MEDIUM, null, maxRetries);
    }

    private JobExecution expiredExecution(Job job, int attemptNumber) {
        JobExecution exec = new JobExecution(
                UUID.randomUUID(), job, "worker-dead", attemptNumber, JobExecutionStatus.RUNNING);
        // Lease expired 1 minute before the fixed clock.
        exec.setLeaseExpiresAt(OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).minusMinutes(1));
        exec.setStartedAt(OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).minusMinutes(6));
        return exec;
    }

    private void stubExpired(List<JobExecution> execs) {
        when(jobExecutionRepository.findExpiredForUpdate(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(execs);
    }
}
