package dev.jwalker.controlplane.api.jobs.service;

import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.jobs.repository.JobRepository;
import dev.jwalker.controlplane.api.jobs.web.dto.JobCreateRequest;
import dev.jwalker.controlplane.api.jobs.web.dto.JobResponse;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
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
    private final UserRepository userRepository;

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

        return JobResponse.from(jobRepository.save(job));
    }

    @Transactional(readOnly = true)
    public Optional<JobResponse> findById(UUID jobId) {
        return jobRepository.findByIdWithRelations(jobId).map(JobResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<JobResponse> search(
            JobStatus status,
            JobType type,
            JobPriority priority,
            UUID ownerId,
            UUID sourceScheduleId,
            Pageable pageable) {
        return jobRepository.search(status, type, priority, ownerId, sourceScheduleId, pageable)
                .map(JobResponse::from);
    }
}
