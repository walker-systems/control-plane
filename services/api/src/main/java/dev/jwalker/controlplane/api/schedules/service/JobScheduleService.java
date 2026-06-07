package dev.jwalker.controlplane.api.schedules.service;

import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.schedules.model.JobSchedule;
import dev.jwalker.controlplane.api.schedules.repository.JobScheduleRepository;
import dev.jwalker.controlplane.api.schedules.web.dto.JobScheduleCreateRequest;
import dev.jwalker.controlplane.api.schedules.web.dto.JobScheduleResponse;
import dev.jwalker.controlplane.api.schedules.web.dto.JobScheduleUpdateRequest;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
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

    @Transactional
    public JobScheduleResponse create(UUID ownerId, JobScheduleCreateRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user not found: " + ownerId));

        CronExpression cron = parseCron(request.cron());
        ZoneId zone = parseTimezone(request.timezone());
        OffsetDateTime nextRunAt = computeNextRunAt(cron, zone);

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
            return JobScheduleResponse.from(jobScheduleRepository.saveAndFlush(schedule));
        } catch (DataIntegrityViolationException e) {
            throw new InvalidScheduleConfigException(
                    InvalidScheduleConfigException.Reason.DUPLICATE_NAME,
                    "A schedule named '" + request.name() + "' already exists for this owner");
        }
    }

    private static CronExpression parseCron(String expression) {
        try {
            return CronExpression.parse(expression);
        } catch (IllegalArgumentException e) {
            throw new InvalidScheduleConfigException(
                    InvalidScheduleConfigException.Reason.INVALID_CRON,
                    "Invalid cron expression: " + expression);
        }
    }

    private static ZoneId parseTimezone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new InvalidScheduleConfigException(
                    InvalidScheduleConfigException.Reason.INVALID_TIMEZONE,
                    "Invalid timezone: " + timezone);
        }
    }

    @Transactional(readOnly = true)
    public Optional<JobScheduleResponse> findById(UUID scheduleId) {
        return jobScheduleRepository.findByIdWithOwner(scheduleId).map(JobScheduleResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<JobScheduleResponse> search(
            Boolean enabled,
            JobType type,
            JobPriority priority,
            UUID ownerId,
            Pageable pageable) {
        return jobScheduleRepository.search(enabled, type, priority, ownerId, pageable)
                .map(JobScheduleResponse::from);
    }

    @Transactional
    public Optional<JobScheduleResponse> pause(UUID scheduleId) {
        Optional<JobSchedule> opt = jobScheduleRepository.findByIdWithOwner(scheduleId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        JobSchedule s = opt.get();
        if (s.isEnabled()) {
            s.disable();
            s.setNextRunAt(null);
            jobScheduleRepository.save(s);
        }
        return Optional.of(JobScheduleResponse.from(s));
    }

    @Transactional
    public Optional<JobScheduleResponse> update(UUID scheduleId, JobScheduleUpdateRequest request) {
        Optional<JobSchedule> opt = jobScheduleRepository.findByIdWithOwner(scheduleId);
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
            parsedCron = parseCron(request.cron());
            s.setCronExpression(request.cron());
        }
        if (request.timezone() != null) {
            parsedZone = parseTimezone(request.timezone());
            s.setTimezone(request.timezone());
        }

        if ((parsedCron != null || parsedZone != null) && s.isEnabled()) {
            CronExpression cron = parsedCron != null ? parsedCron : parseCron(s.getCronExpression());
            ZoneId zone = parsedZone != null ? parsedZone : parseTimezone(s.getTimezone());
            s.setNextRunAt(computeNextRunAt(cron, zone));
        }

        s.touch();

        try {
            return Optional.of(JobScheduleResponse.from(jobScheduleRepository.saveAndFlush(s)));
        } catch (DataIntegrityViolationException e) {
            throw new InvalidScheduleConfigException(
                    InvalidScheduleConfigException.Reason.DUPLICATE_NAME,
                    "A schedule named '" + s.getName() + "' already exists for this owner");
        }
    }

    @Transactional
    public boolean delete(UUID scheduleId) {
        Optional<JobSchedule> opt = jobScheduleRepository.findByIdWithOwner(scheduleId);
        if (opt.isEmpty()) {
            return false;
        }
        jobScheduleRepository.delete(opt.get());
        return true;
    }

    @Transactional
    public Optional<JobScheduleResponse> resume(UUID scheduleId) {
        Optional<JobSchedule> opt = jobScheduleRepository.findByIdWithOwner(scheduleId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        JobSchedule s = opt.get();
        if (!s.isEnabled()) {
            CronExpression cron = parseCron(s.getCronExpression());
            ZoneId zone = parseTimezone(s.getTimezone());
            s.enable();
            s.setNextRunAt(computeNextRunAt(cron, zone));
            jobScheduleRepository.save(s);
        }
        return Optional.of(JobScheduleResponse.from(s));
    }

    static OffsetDateTime computeNextRunAt(CronExpression cron, ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        Temporal next = cron.next(now);
        return next == null ? null : ((ZonedDateTime) next).toOffsetDateTime();
    }
}
