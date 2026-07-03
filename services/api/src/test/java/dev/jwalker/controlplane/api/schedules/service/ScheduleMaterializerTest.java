package dev.jwalker.controlplane.api.schedules.service;

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
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.jobs.repository.JobRepository;
import dev.jwalker.controlplane.api.schedules.model.JobSchedule;
import dev.jwalker.controlplane.api.schedules.repository.JobScheduleRepository;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ScheduleMaterializerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-03T12:00:30Z");

    @Mock
    private JobScheduleRepository jobScheduleRepository;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private AuditEventService auditEventService;

    private final Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    @InjectMocks
    private ScheduleMaterializer materializer;

    @BeforeEach
    void wireClockAndBatchSize() {
        materializer = new ScheduleMaterializer(
                jobScheduleRepository, jobRepository, auditEventService, fixedClock);
        ReflectionTestUtils.setField(materializer, "batchSize", 50);
    }

    @Test
    void materializeDue_createsJobAndAdvancesNextRunAt_forDueSchedule() {
        JobSchedule schedule = dueEveryMinuteSchedule(Duration.ofSeconds(5));
        when(jobScheduleRepository.findDueForUpdate(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(schedule));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
            Job j = inv.getArgument(0);
            j.setId(UUID.randomUUID());
            return j;
        });

        int fired = materializer.materializeDue();

        assertThat(fired).isEqualTo(1);
        ArgumentCaptor<Job> jobCap = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(jobCap.capture());
        Job savedJob = jobCap.getValue();
        assertThat(savedJob.getSourceScheduleId()).isEqualTo(schedule.getId());
        assertThat(savedJob.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(savedJob.getType()).isEqualTo(schedule.getType());
        assertThat(savedJob.getPriority()).isEqualTo(schedule.getPriority());
        assertThat(savedJob.getOwner()).isEqualTo(schedule.getOwner());

        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
        assertThat(schedule.getNextRunAt()).isAfter(now);
        assertThat(schedule.getLastEnqueuedAt()).isEqualTo(now);
    }

    @Test
    void materializeDue_skipsForwardWhenNextRunAtIsWayBehind() {
        // Cron: every hour on the hour. Schedule was supposed to run 3 hours ago.
        // With skip-forward we fire exactly once, and nextRunAt lands past `now`.
        JobSchedule schedule = new JobSchedule(
                UUID.randomUUID(),
                dummyOwner(),
                "hourly",
                JobType.CRM_SYNC,
                null,
                JobPriority.MEDIUM,
                3,
                "0 0 * * * *",
                "UTC");
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
        schedule.setNextRunAt(now.minusHours(3));

        when(jobScheduleRepository.findDueForUpdate(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(schedule));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
            Job j = inv.getArgument(0);
            j.setId(UUID.randomUUID());
            return j;
        });

        materializer.materializeDue();

        verify(jobRepository, times(1)).save(any(Job.class));
        assertThat(schedule.getNextRunAt()).isAfter(now);
        // 12:00:30 → next hourly slot is 13:00:00, not 10:00:00.
        assertThat(schedule.getNextRunAt().getHour()).isEqualTo(13);
    }

    @Test
    void materializeDue_emitsScheduleFiredAudit_withJobIdMetadata() {
        JobSchedule schedule = dueEveryMinuteSchedule(Duration.ofSeconds(1));
        UUID jobId = UUID.randomUUID();
        when(jobScheduleRepository.findDueForUpdate(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(schedule));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
            Job j = inv.getArgument(0);
            j.setId(jobId);
            return j;
        });

        materializer.materializeDue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCap =
                ArgumentCaptor.forClass(Map.class);
        verify(auditEventService).recordWithActor(
                eq(AuditEventType.SCHEDULE_FIRED),
                isNull(),
                eq("JobSchedule"),
                eq(schedule.getId()),
                metaCap.capture());
        Map<String, Object> meta = metaCap.getValue();
        assertThat(meta).containsEntry("jobId", jobId.toString());
        assertThat(meta).containsKey("firedAt");
    }

    @Test
    void materializeDue_processesMultipleSchedulesInOneTick() {
        JobSchedule s1 = dueEveryMinuteSchedule(Duration.ofSeconds(1));
        JobSchedule s2 = dueEveryMinuteSchedule(Duration.ofSeconds(2));
        JobSchedule s3 = dueEveryMinuteSchedule(Duration.ofSeconds(3));
        when(jobScheduleRepository.findDueForUpdate(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(s1, s2, s3));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
            Job j = inv.getArgument(0);
            j.setId(UUID.randomUUID());
            return j;
        });

        int fired = materializer.materializeDue();

        assertThat(fired).isEqualTo(3);
        verify(jobRepository, times(3)).save(any(Job.class));
        verify(auditEventService, times(3)).recordWithActor(
                eq(AuditEventType.SCHEDULE_FIRED), isNull(), eq("JobSchedule"), any(), any());
    }

    @Test
    void materializeDue_returnsZero_whenNothingDue() {
        when(jobScheduleRepository.findDueForUpdate(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

        int fired = materializer.materializeDue();

        assertThat(fired).isZero();
        verify(jobRepository, never()).save(any(Job.class));
        verify(auditEventService, never()).recordWithActor(any(), any(), any(), any(), any());
    }

    private JobSchedule dueEveryMinuteSchedule(Duration ago) {
        JobSchedule schedule = new JobSchedule(
                UUID.randomUUID(),
                dummyOwner(),
                "every-minute",
                JobType.CRM_SYNC,
                "{\"k\":\"v\"}",
                JobPriority.MEDIUM,
                3,
                "0 * * * * *",
                "UTC");
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
        schedule.setNextRunAt(now.minus(ago));
        return schedule;
    }

    private static User dummyOwner() {
        return new User(UUID.randomUUID(), "owner@example.com", "hash", UserStatus.ACTIVE);
    }
}
