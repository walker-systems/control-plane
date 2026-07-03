package dev.jwalker.controlplane.api.schedules.service;

import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.service.AuditEventService;
import dev.jwalker.controlplane.api.auth.service.AuthenticatedCaller;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.schedules.model.JobSchedule;
import dev.jwalker.controlplane.api.schedules.repository.JobScheduleRepository;
import dev.jwalker.controlplane.api.schedules.web.dto.JobScheduleCreateRequest;
import dev.jwalker.controlplane.api.schedules.web.dto.JobScheduleResponse;
import dev.jwalker.controlplane.api.schedules.web.dto.JobScheduleUpdateRequest;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JobScheduleService {

    private static final JobPriority DEFAULT_PRIORITY = JobPriority.MEDIUM;
    private static final int DEFAULT_MAX_RETRIES = 3;

    private final JobScheduleRepository jobScheduleRepository;
    private final UserRepository userRepository;
    private final AuditEventService auditEventService;

    @Transactional
    public JobScheduleResponse create(UUID ownerId, JobScheduleCreateRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user not found: " + ownerId));

        CronExpression cron = ScheduleCronCalculator.parseCron(request.cron());
        ZoneId zone = ScheduleCronCalculator.parseZone(request.timezone());
        OffsetDateTime nextRunAt = ScheduleCronCalculator.nextAfter(cron, zone, OffsetDateTime.now());

        JobSchedule schedule = new JobSchedule(
                null,
                owner,
                request.name(),
                request.type(),
                request.payloadJson(),
                request.priority() == null ? DEFAULT_PRIORITY : request.priority(),
                request.maxRetries() == null ? DEFAULT_MAX_RETRIES : request.maxRetries(),
                request.cron(),
                request.timezone());
        schedule.setNextRunAt(nextRunAt);

        try {
            JobSchedule saved = jobScheduleRepository.saveAndFlush(schedule);
            auditEventService.record(
                    AuditEventType.SCHEDULE_CREATED,
                    "JobSchedule",
                    saved.getId(),
                    Map.of(
                            "name", saved.getName(),
                            "type", saved.getType().name(),
                            "priority", saved.getPriority().name()));
            return JobScheduleResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            throw new InvalidScheduleConfigException(
                    InvalidScheduleConfigException.Reason.DUPLICATE_NAME,
                    "A schedule named '" + request.name() + "' already exists for this owner");
        }
    }

    @Transactional(readOnly = true)
    public Optional<JobScheduleResponse> findById(UUID scheduleId, AuthenticatedCaller caller) {
        return jobScheduleRepository.findByIdWithOwner(scheduleId)
                .filter(s -> canAccess(s, caller))
                .map(JobScheduleResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<JobScheduleResponse> search(
            Boolean enabled,
            JobType type,
            JobPriority priority,
            UUID ownerId,
            Pageable pageable,
            AuthenticatedCaller caller) {
        UUID effectiveOwnerId = caller.isPrivileged() ? ownerId : caller.userId();
        return jobScheduleRepository.search(enabled, type, priority, effectiveOwnerId, pageable)
                .map(JobScheduleResponse::from);
    }

    @Transactional
    public Optional<JobScheduleResponse> pause(UUID scheduleId, AuthenticatedCaller caller) {
        Optional<JobSchedule> opt = jobScheduleRepository.findByIdWithOwner(scheduleId)
                .filter(s -> canAccess(s, caller));
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        JobSchedule s = opt.get();
        if (s.isEnabled()) {
            s.disable();
            s.setNextRunAt(null);
            jobScheduleRepository.save(s);
            auditEventService.record(
                    AuditEventType.SCHEDULE_PAUSED,
                    "JobSchedule",
                    s.getId(),
                    Map.of("name", s.getName()));
        }
        return Optional.of(JobScheduleResponse.from(s));
    }

    // Partial update: each request field that's non-null is applied; null
    // fields are left alone. Cron and timezone re-validate via the same
    // parsers used on create, throwing InvalidScheduleConfigException with
    // an appropriate Reason that the controller's handler maps to 400. The
    // unique-name violation translates to 409 the same way create does.
    //
    // nextRunAt is recomputed only when cron or timezone actually changed
    // AND the schedule is enabled. Paused schedules keep nextRunAt = null;
    // the new cron/tz takes effect when the user next resumes.
    @Transactional
    public Optional<JobScheduleResponse> update(UUID scheduleId, JobScheduleUpdateRequest request, AuthenticatedCaller caller) {
        Optional<JobSchedule> opt = jobScheduleRepository.findByIdWithOwner(scheduleId)
                .filter(s -> canAccess(s, caller));
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        JobSchedule s = opt.get();

        CronExpression parsedCron = null;
        ZoneId parsedZone = null;

        if (request.name() != null) {
            s.setName(request.name());
        }
        if (request.payloadJson() != null) {
            s.setPayloadJson(request.payloadJson());
        }
        if (request.priority() != null) {
            s.setPriority(request.priority());
        }
        if (request.maxRetries() != null) {
            s.setMaxRetries(request.maxRetries());
        }
        if (request.cron() != null) {
            parsedCron = ScheduleCronCalculator.parseCron(request.cron());
            s.setCronExpression(request.cron());
        }
        if (request.timezone() != null) {
            parsedZone = ScheduleCronCalculator.parseZone(request.timezone());
            s.setTimezone(request.timezone());
        }

        if ((parsedCron != null || parsedZone != null) && s.isEnabled()) {
            CronExpression cron = parsedCron != null ? parsedCron : ScheduleCronCalculator.parseCron(s.getCronExpression());
            ZoneId zone = parsedZone != null ? parsedZone : ScheduleCronCalculator.parseZone(s.getTimezone());
            s.setNextRunAt(ScheduleCronCalculator.nextAfter(cron, zone, OffsetDateTime.now()));
        }

        s.touch();

        try {
            JobSchedule saved = jobScheduleRepository.saveAndFlush(s);
            auditEventService.record(
                    AuditEventType.SCHEDULE_UPDATED,
                    "JobSchedule",
                    saved.getId(),
                    Map.of("name", saved.getName()));
            return Optional.of(JobScheduleResponse.from(saved));
        } catch (DataIntegrityViolationException e) {
            throw new InvalidScheduleConfigException(
                    InvalidScheduleConfigException.Reason.DUPLICATE_NAME,
                    "A schedule named '" + s.getName() + "' already exists for this owner");
        }
    }

    // Soft delete: repository.delete() is intercepted by the entity's
    // @SQLDelete annotation, which runs an UPDATE setting deleted_at instead
    // of an actual DELETE. The row stays in the table for lineage queries
    // (jobs created from this schedule still reference its id) but vanishes
    // from normal API queries via @SQLRestriction. Returning a boolean lets
    // the controller decide between 204 (found and deleted) and 404 (missing).
    @Transactional
    public boolean delete(UUID scheduleId, AuthenticatedCaller caller) {
        Optional<JobSchedule> opt = jobScheduleRepository.findByIdWithOwner(scheduleId)
                .filter(s -> canAccess(s, caller));
        if (opt.isEmpty()) {
            return false;
        }
        JobSchedule s = opt.get();
        jobScheduleRepository.delete(s);
        auditEventService.record(
                AuditEventType.SCHEDULE_DELETED,
                "JobSchedule",
                s.getId(),
                Map.of("name", s.getName()));
        return true;
    }

    @Transactional
    public Optional<JobScheduleResponse> resume(UUID scheduleId, AuthenticatedCaller caller) {
        Optional<JobSchedule> opt = jobScheduleRepository.findByIdWithOwner(scheduleId)
                .filter(s -> canAccess(s, caller));
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        JobSchedule s = opt.get();
        if (!s.isEnabled()) {
            CronExpression cron = ScheduleCronCalculator.parseCron(s.getCronExpression());
            ZoneId zone = ScheduleCronCalculator.parseZone(s.getTimezone());
            s.enable();
            s.setNextRunAt(ScheduleCronCalculator.nextAfter(cron, zone, OffsetDateTime.now()));
            jobScheduleRepository.save(s);
            auditEventService.record(
                    AuditEventType.SCHEDULE_RESUMED,
                    "JobSchedule",
                    s.getId(),
                    Map.of("name", s.getName()));
        }
        return Optional.of(JobScheduleResponse.from(s));
    }

    private static boolean canAccess(JobSchedule schedule, AuthenticatedCaller caller) {
        return caller.isPrivileged() || schedule.getOwner().getId().equals(caller.userId());
    }
}
