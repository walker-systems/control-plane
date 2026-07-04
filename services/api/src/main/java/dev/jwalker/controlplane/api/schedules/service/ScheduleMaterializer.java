package dev.jwalker.controlplane.api.schedules.service;

import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.service.AuditEventService;
import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.repository.JobRepository;
import dev.jwalker.controlplane.api.schedules.model.JobSchedule;
import dev.jwalker.controlplane.api.schedules.repository.JobScheduleRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleMaterializer {

    private final JobScheduleRepository jobScheduleRepository;
    private final JobRepository jobRepository;
    private final AuditEventService auditEventService;
    private final Clock clock;

    @Value("${app.scheduling.batch-size:50}")
    int batchSize;

    // One tick, one transaction. Locks up to batchSize due schedules with
    // FOR UPDATE SKIP LOCKED so parallel API instances materialize disjoint
    // slices without blocking each other. Returns the count of jobs
    // materialized so the caller can log/instrument.
    @Transactional
    public int materializeDue() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<JobSchedule> due = jobScheduleRepository.findDueForUpdate(
                now, PageRequest.of(0, batchSize));
        for (JobSchedule schedule : due) {
            materializeOne(schedule, now);
        }
        return due.size();
    }

    private void materializeOne(JobSchedule schedule, OffsetDateTime now) {
        Job job = new Job(
                null,
                schedule.getOwner(),
                schedule.getId(),
                schedule.getType(),
                schedule.getPayloadJson(),
                JobStatus.PENDING,
                schedule.getPriority(),
                null,
                schedule.getMaxRetries());
        Job savedJob = jobRepository.save(job);

        OffsetDateTime nextSlot = advanceNextRunAt(schedule, now);
        schedule.setNextRunAt(nextSlot);
        schedule.setLastEnqueuedAt(now);
        schedule.touch();

        auditEventService.recordWithActor(
                AuditEventType.SCHEDULE_FIRED,
                null,
                "JobSchedule",
                schedule.getId(),
                Map.of(
                        "jobId", savedJob.getId().toString(),
                        "firedAt", now.toString()));

        log.info("Materialized job {} from schedule {} (fired at {}, next at {})",
                savedJob.getId(), schedule.getId(), now, nextSlot);
    }

    // Skip-forward misfire policy: nextRunAt lands strictly after `now`, so
    // a long downtime doesn't produce a burst of catchup jobs. If the cron
    // has no further matches, nextRunAt goes null and the schedule stays
    // enabled; the operator can update the cron to bring it back.
    private OffsetDateTime advanceNextRunAt(JobSchedule schedule, OffsetDateTime now) {
        CronExpression cron = ScheduleCronCalculator.parseCron(schedule.getCronExpression());
        ZoneId zone = ScheduleCronCalculator.parseZone(schedule.getTimezone());
        OffsetDateTime next = ScheduleCronCalculator.nextAfter(cron, zone, now);
        if (next == null) {
            log.warn("Schedule {} has no further cron matches; nextRunAt set to null (still enabled)",
                    schedule.getId());
        }
        return next;
    }
}
