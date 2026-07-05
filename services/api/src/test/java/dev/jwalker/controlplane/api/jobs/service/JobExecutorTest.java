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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class JobExecutorTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-04T12:00:00Z");

    @Mock private JobRepository jobRepository;
    @Mock private JobExecutionRepository jobExecutionRepository;
    @Mock private AuditEventService auditEventService;
    @Mock private JobHandlerRegistry handlerRegistry;
    @Mock private JobHandler handler;

    private final Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private JobExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new JobExecutor(
                jobRepository, jobExecutionRepository, auditEventService, handlerRegistry, fixedClock);
        ReflectionTestUtils.setField(executor, "batchSize", 10);
    }

    @Test
    void processPending_marksJobSucceeded_whenHandlerReturns() throws Exception {
        Job job = pendingJob();
        stubPending(List.of(job));
        stubPriorExecutions(job, 0);
        when(handlerRegistry.handlerFor(job.getType())).thenReturn(Optional.of(handler));
        when(handler.handle(job)).thenReturn("done");
        stubSaveExecutionEchoesArgument();

        int processed = executor.processPending();

        assertThat(processed).isEqualTo(1);
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);

        ArgumentCaptor<JobExecution> execCap = ArgumentCaptor.forClass(JobExecution.class);
        verify(jobExecutionRepository).save(execCap.capture());
        JobExecution exec = execCap.getValue();
        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.SUCCEEDED);
        assertThat(exec.getAttemptNumber()).isEqualTo(1);
        assertThat(exec.getOutputSummary()).isEqualTo("done");

        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_STARTED), isNull(), eq("Job"), eq(job.getId()), any());
        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_SUCCEEDED), isNull(), eq("Job"), eq(job.getId()), any());
        verify(auditEventService, never()).recordWithActor(
                eq(AuditEventType.JOB_FAILED), any(), any(), any(), any());
    }

    @Test
    void processPending_marksJobFailed_whenHandlerThrows() throws Exception {
        Job job = pendingJob();
        stubPending(List.of(job));
        stubPriorExecutions(job, 0);
        when(handlerRegistry.handlerFor(job.getType())).thenReturn(Optional.of(handler));
        when(handler.handle(job)).thenThrow(new RuntimeException("boom"));
        stubSaveExecutionEchoesArgument();

        executor.processPending();

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);

        ArgumentCaptor<JobExecution> execCap = ArgumentCaptor.forClass(JobExecution.class);
        verify(jobExecutionRepository).save(execCap.capture());
        JobExecution exec = execCap.getValue();
        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.FAILED);
        assertThat(exec.getErrorMessage()).isEqualTo("boom");

        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_STARTED), isNull(), eq("Job"), eq(job.getId()), any());
        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_FAILED), isNull(), eq("Job"), eq(job.getId()), any());
        // Commit 1: no retry / no DEAD_LETTER escalation on a plain failure.
        verify(auditEventService, never()).recordWithActor(
                eq(AuditEventType.JOB_DEAD_LETTERED), any(), any(), any(), any());
    }

    @Test
    void processPending_deadLettersJob_whenNoHandlerRegistered() {
        Job job = pendingJob();
        stubPending(List.of(job));
        stubPriorExecutions(job, 0);
        when(handlerRegistry.handlerFor(job.getType())).thenReturn(Optional.empty());
        stubSaveExecutionEchoesArgument();

        executor.processPending();

        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD_LETTER);

        ArgumentCaptor<JobExecution> execCap = ArgumentCaptor.forClass(JobExecution.class);
        verify(jobExecutionRepository).save(execCap.capture());
        JobExecution exec = execCap.getValue();
        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.FAILED);
        assertThat(exec.getErrorMessage()).contains("No handler registered");

        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_FAILED), isNull(), eq("Job"), eq(job.getId()), any());
        verify(auditEventService).recordWithActor(
                eq(AuditEventType.JOB_DEAD_LETTERED), isNull(), eq("Job"), eq(job.getId()), any());
        // Job never reached RUNNING — no JOB_STARTED for the missing-handler path.
        verify(auditEventService, never()).recordWithActor(
                eq(AuditEventType.JOB_STARTED), any(), any(), any(), any());
    }

    @Test
    void processPending_incrementsAttemptNumberAcrossRuns() throws Exception {
        Job job = pendingJob();
        stubPending(List.of(job));
        stubPriorExecutions(job, 2);
        when(handlerRegistry.handlerFor(job.getType())).thenReturn(Optional.of(handler));
        when(handler.handle(job)).thenReturn("done");
        stubSaveExecutionEchoesArgument();

        executor.processPending();

        ArgumentCaptor<JobExecution> execCap = ArgumentCaptor.forClass(JobExecution.class);
        verify(jobExecutionRepository).save(execCap.capture());
        assertThat(execCap.getValue().getAttemptNumber()).isEqualTo(3);
    }

    @Test
    void processPending_processesMultipleJobs() throws Exception {
        Job j1 = pendingJob();
        Job j2 = pendingJob();
        Job j3 = pendingJob();
        stubPending(List.of(j1, j2, j3));
        stubPriorExecutions(j1, 0);
        stubPriorExecutions(j2, 0);
        stubPriorExecutions(j3, 0);
        when(handlerRegistry.handlerFor(any())).thenReturn(Optional.of(handler));
        when(handler.handle(any())).thenReturn("done");
        stubSaveExecutionEchoesArgument();

        int processed = executor.processPending();

        assertThat(processed).isEqualTo(3);
        assertThat(j1.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(j2.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(j3.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void processPending_returnsZero_whenNothingPending() {
        stubPending(List.of());

        int processed = executor.processPending();

        assertThat(processed).isZero();
        verify(jobExecutionRepository, never()).save(any(JobExecution.class));
        verify(auditEventService, never()).recordWithActor(any(), any(), any(), any(), any());
    }

    // --- helpers ---

    private Job pendingJob() {
        User owner = new User(UUID.randomUUID(), "owner@example.com", "hash", UserStatus.ACTIVE);
        Job j = new Job(
                UUID.randomUUID(), owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.PENDING, JobPriority.MEDIUM, null, 3);
        return j;
    }

    private void stubPending(List<Job> jobs) {
        when(jobRepository.findPendingForUpdate(any(Pageable.class))).thenReturn(jobs);
    }

    private void stubPriorExecutions(Job job, int count) {
        List<JobExecution> prior = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            prior.add(new JobExecution(
                    UUID.randomUUID(), job, "in-process", i + 1, JobExecutionStatus.FAILED));
        }
        when(jobExecutionRepository.findByJob(job)).thenReturn(prior);
    }

    private void stubSaveExecutionEchoesArgument() {
        when(jobExecutionRepository.save(any(JobExecution.class))).thenAnswer(inv -> {
            JobExecution e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return e;
        });
    }
}
