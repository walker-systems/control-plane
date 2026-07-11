package dev.jwalker.controlplane.api.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
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
import org.springframework.test.util.ReflectionTestUtils;

// Orchestration test: verifies JobExecutor's pick → run → complete
// dispatch. State machine details (what completeSuccess actually does
// to Job/JobExecution rows) live in JobExecutorTxOpsTest.
@ExtendWith(MockitoExtension.class)
class JobExecutorTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-08T12:00:00Z");

    @Mock private JobExecutorTxOps txOps;
    @Mock private JobHandlerRegistry handlerRegistry;
    @Mock private JobHandler handler;

    private final Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private JobExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new JobExecutor(txOps, handlerRegistry, fixedClock);
        ReflectionTestUtils.setField(executor, "batchSize", 10);
    }

    @Test
    void processPending_dispatchesToCompleteSuccess_whenHandlerReturns() throws Exception {
        PickedJob picked = pickedJob();
        stubPicked(List.of(picked));
        when(handlerRegistry.handlerFor(picked.job().getType())).thenReturn(Optional.of(handler));
        when(handler.handle(picked.job())).thenReturn("done");

        int processed = executor.processPending();

        assertThat(processed).isEqualTo(1);
        verify(txOps).completeSuccess(
                eq(picked.job().getId()), eq(picked.execId()),
                eq(picked.attemptNumber()), eq("done"));
        verify(txOps, never()).completeFailure(any(), any(), anyInt(), any(), any());
        verify(txOps, never()).completeMissingHandler(any(), any(), any(), anyInt());
    }

    @Test
    void processPending_dispatchesToCompleteFailure_whenHandlerThrows() throws Exception {
        PickedJob picked = pickedJob();
        stubPicked(List.of(picked));
        when(handlerRegistry.handlerFor(picked.job().getType())).thenReturn(Optional.of(handler));
        when(handler.handle(picked.job())).thenThrow(new RuntimeException("boom"));

        executor.processPending();

        verify(txOps).completeFailure(
                eq(picked.job().getId()), eq(picked.execId()),
                eq(picked.attemptNumber()), eq("boom"), any(OffsetDateTime.class));
        verify(txOps, never()).completeSuccess(any(), any(), anyInt(), any());
    }

    @Test
    void processPending_dispatchesToCompleteMissingHandler_whenHandlerAbsent() {
        PickedJob picked = pickedJob();
        stubPicked(List.of(picked));
        when(handlerRegistry.handlerFor(picked.job().getType())).thenReturn(Optional.empty());

        executor.processPending();

        verify(txOps).completeMissingHandler(
                eq(picked.job().getId()), eq(picked.execId()),
                eq(picked.job().getType()), eq(picked.attemptNumber()));
        verify(txOps, never()).completeSuccess(any(), any(), anyInt(), any());
        verify(txOps, never()).completeFailure(any(), any(), anyInt(), any(), any());
    }

    @Test
    void processPending_processesMultipleJobs() throws Exception {
        PickedJob p1 = pickedJob();
        PickedJob p2 = pickedJob();
        PickedJob p3 = pickedJob();
        stubPicked(List.of(p1, p2, p3));
        when(handlerRegistry.handlerFor(any())).thenReturn(Optional.of(handler));
        when(handler.handle(any())).thenReturn("done");

        int processed = executor.processPending();

        assertThat(processed).isEqualTo(3);
        verify(txOps, times(3)).completeSuccess(any(), any(), anyInt(), eq("done"));
    }

    @Test
    void processPending_returnsZero_whenNothingPicked() {
        stubPicked(List.of());

        int processed = executor.processPending();

        assertThat(processed).isZero();
        verify(txOps, never()).completeSuccess(any(), any(), anyInt(), any());
        verify(txOps, never()).completeFailure(any(), any(), anyInt(), any(), any());
        verify(txOps, never()).completeMissingHandler(any(), any(), any(), anyInt());
    }

    @Test
    void processPending_usesClassNameAsError_whenHandlerThrowsWithNullMessage() throws Exception {
        PickedJob picked = pickedJob();
        stubPicked(List.of(picked));
        when(handlerRegistry.handlerFor(picked.job().getType())).thenReturn(Optional.of(handler));
        when(handler.handle(picked.job())).thenThrow(new NullPointerException());

        executor.processPending();

        verify(txOps).completeFailure(
                eq(picked.job().getId()), eq(picked.execId()),
                eq(picked.attemptNumber()),
                eq("NullPointerException"),
                any(OffsetDateTime.class));
    }

    // --- helpers -------------------------------------------------------

    private PickedJob pickedJob() {
        User owner = new User(UUID.randomUUID(), "owner@example.com", "hash", UserStatus.ACTIVE);
        Job job = new Job(
                UUID.randomUUID(), owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.RUNNING, JobPriority.MEDIUM, null, 3);
        return new PickedJob(job, UUID.randomUUID(), 1);
    }

    private void stubPicked(List<PickedJob> picked) {
        when(txOps.pickBatch(any(OffsetDateTime.class), any(Integer.class))).thenReturn(picked);
    }
}
