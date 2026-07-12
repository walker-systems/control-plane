package dev.jwalker.controlplane.api.jobs.service;

import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.service.AuditEventService;
import dev.jwalker.controlplane.api.auth.service.AuthenticatedCaller;
import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.jobs.repository.JobExecutionRepository;
import dev.jwalker.controlplane.api.jobs.repository.JobExecutionRepository.JobAttemptCount;
import dev.jwalker.controlplane.api.jobs.repository.JobRepository;
import dev.jwalker.controlplane.api.jobs.repository.JobRepository.JobStatusCount;
import dev.jwalker.controlplane.api.jobs.web.dto.JobCreateRequest;
import dev.jwalker.controlplane.api.jobs.web.dto.JobResponse;
import dev.jwalker.controlplane.api.jobs.web.dto.JobStatsResponse;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JobService {

    private static final JobPriority DEFAULT_PRIORITY = JobPriority.MEDIUM;
    private static final int DEFAULT_MAX_RETRIES = 3;

    private final JobRepository jobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final UserRepository userRepository;
    private final AuditEventService auditEventService;

    @Transactional
    public JobResponse create(UUID ownerId, JobCreateRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user not found: " + ownerId));

        Job job = new Job(
                null,
                owner,
                null,
                request.type(),
                request.payloadJson(),
                JobStatus.PENDING,
                request.priority() == null ? DEFAULT_PRIORITY : request.priority(),
                request.idempotencyKey(),
                request.maxRetries() == null ? DEFAULT_MAX_RETRIES : request.maxRetries());

        Job saved = jobRepository.save(job);
        auditEventService.record(
                AuditEventType.JOB_CREATED,
                "Job",
                saved.getId(),
                Map.of(
                        "type", saved.getType().name(),
                        "priority", saved.getPriority().name()));
        // Fresh job — no executions yet, so attempt count is definitionally zero.
        return JobResponse.from(saved, 0L);
    }

    @Transactional(readOnly = true)
    public Optional<JobResponse> findById(UUID jobId, AuthenticatedCaller caller) {
        return jobRepository.findByIdWithRelations(jobId)
                .filter(job -> canAccess(job, caller))
                .map(job -> JobResponse.from(job, jobExecutionRepository.countByJob_Id(job.getId())));
    }

    @Transactional(readOnly = true)
    public Page<JobResponse> search(
            JobStatus status,
            JobType type,
            JobPriority priority,
            UUID ownerId,
            UUID sourceScheduleId,
            Pageable pageable,
            AuthenticatedCaller caller) {
        UUID effectiveOwnerId = caller.isPrivileged() ? ownerId : caller.userId();
        Page<Job> jobPage = jobRepository.search(
                status, type, priority, effectiveOwnerId, sourceScheduleId, pageable);
        // Bulk-fetch attempt counts for every job in the page in one query
        // rather than N per-job COUNT queries. Jobs with no executions
        // aren't returned by the grouped query — getOrDefault fills 0.
        Map<UUID, Long> counts = executionCountsFor(jobPage.getContent());
        return jobPage.map(job -> JobResponse.from(job, counts.getOrDefault(job.getId(), 0L)));
    }

    // Counts by JobStatus for the caller's visible jobs. USER role sees
    // their own; OPERATOR/ADMIN see all. Every status is represented in
    // the response even when its count is zero — the DTO fills gaps.
    @Transactional(readOnly = true)
    public JobStatsResponse stats(AuthenticatedCaller caller) {
        UUID ownerFilter = caller.isPrivileged() ? null : caller.userId();
        Map<JobStatus, Long> raw = jobRepository.countByStatus(ownerFilter).stream()
                .collect(Collectors.toMap(JobStatusCount::getStatus, JobStatusCount::getCount));
        return JobStatsResponse.from(raw);
    }

    @Transactional
    public Optional<JobResponse> cancel(UUID jobId, AuthenticatedCaller caller) {
        // Locking read: if the executor's complete phase is mid-tx on this
        // row, wait for it to commit before checking status. That way the
        // status check below sees the post-commit state and correctly
        // refuses cancel on terminal outcomes rather than trampling them.
        Optional<Job> jobOpt = jobRepository.findByIdWithRelationsForUpdate(jobId)
                .filter(job -> canAccess(job, caller));
        if (jobOpt.isEmpty()) {
            return Optional.empty();
        }
        Job job = jobOpt.get();
        OffsetDateTime now = OffsetDateTime.now();
        JobStatus current = job.getStatus();

        if (current == JobStatus.PENDING) {
            // Immediate cancel: job hasn't been picked up yet.
            job.setStatus(JobStatus.CANCELLED);
            job.setCancelRequestedAt(now);
            job.touch();
            Job saved = jobRepository.save(job);
            auditEventService.record(
                    AuditEventType.JOB_CANCELLED,
                    "Job",
                    saved.getId(),
                    Map.of("previousStatus", "PENDING"));
            return Optional.of(JobResponse.from(saved, jobExecutionRepository.countByJob_Id(saved.getId())));
        }

        if (current == JobStatus.RUNNING) {
            // Deferred cancel: executor holds no row lock during handler
            // execution (per the pick/run/complete split), so setting the
            // flag doesn't block behind the handler. The executor's
            // complete phase or the watchdog will honor the flag when
            // finalizing outcome. Idempotent — setting again just
            // refreshes the timestamp.
            job.setCancelRequestedAt(now);
            job.touch();
            Job saved = jobRepository.save(job);
            return Optional.of(JobResponse.from(saved, jobExecutionRepository.countByJob_Id(saved.getId())));
        }

        // SUCCEEDED / FAILED / DEAD_LETTER / CANCELLED — all terminal.
        throw new JobStateException(
                JobStateException.Reason.CANNOT_CANCEL,
                "Cannot cancel job in status " + current);
    }

    @Transactional
    public Optional<JobResponse> retry(UUID jobId, AuthenticatedCaller caller) {
        Optional<Job> jobOpt = jobRepository.findByIdWithRelations(jobId)
                .filter(job -> canAccess(job, caller));
        if (jobOpt.isEmpty()) {
            return Optional.empty();
        }
        Job job = jobOpt.get();
        if (job.getStatus() != JobStatus.DEAD_LETTER) {
            throw new JobStateException(
                    JobStateException.Reason.CANNOT_RETRY,
                    "Cannot retry job in status " + job.getStatus());
        }
        JobStatus previous = job.getStatus();
        job.setStatus(JobStatus.PENDING);
        job.touch();
        Job saved = jobRepository.save(job);
        auditEventService.record(
                AuditEventType.JOB_RETRIED,
                "Job",
                saved.getId(),
                Map.of("previousStatus", previous.name()));
        return Optional.of(JobResponse.from(saved, jobExecutionRepository.countByJob_Id(saved.getId())));
    }

    private Map<UUID, Long> executionCountsFor(List<Job> jobs) {
        if (jobs.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = jobs.stream().map(Job::getId).toList();
        return jobExecutionRepository.countAttemptsByJobIds(ids).stream()
                .collect(Collectors.toMap(JobAttemptCount::getJobId, JobAttemptCount::getAttemptCount));
    }

    private static boolean canAccess(Job job, AuthenticatedCaller caller) {
        return caller.isPrivileged() || job.getOwner().getId().equals(caller.userId());
    }
}
