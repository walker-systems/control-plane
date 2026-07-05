package dev.jwalker.controlplane.api.jobs.service;

import dev.jwalker.controlplane.api.auth.service.AuthenticatedCaller;
import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.repository.JobExecutionRepository;
import dev.jwalker.controlplane.api.jobs.repository.JobRepository;
import dev.jwalker.controlplane.api.jobs.web.dto.JobExecutionResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JobExecutionService {

    private final JobRepository jobRepository;
    private final JobExecutionRepository jobExecutionRepository;

    // Both methods gate on the parent Job's ownership so an unprivileged
    // caller can't enumerate executions for jobs they can't see. Missing
    // job and access-denied both surface as Optional.empty() rather than
    // distinct outcomes — the controller maps that to 404 without leaking
    // existence, matching the Job endpoints' behavior.
    @Transactional(readOnly = true)
    public Optional<List<JobExecutionResponse>> findAllForJob(UUID jobId, AuthenticatedCaller caller) {
        return jobRepository.findByIdWithRelations(jobId)
                .filter(job -> canAccess(job, caller))
                .map(job -> jobExecutionRepository.findByJobOrderByAttemptNumberAsc(job).stream()
                        .map(JobExecutionResponse::from)
                        .toList());
    }

    @Transactional(readOnly = true)
    public Optional<JobExecutionResponse> findByAttempt(
            UUID jobId, int attemptNumber, AuthenticatedCaller caller) {
        return jobRepository.findByIdWithRelations(jobId)
                .filter(job -> canAccess(job, caller))
                .flatMap(job -> jobExecutionRepository.findByJob_IdAndAttemptNumber(jobId, attemptNumber))
                .map(JobExecutionResponse::from);
    }

    private static boolean canAccess(Job job, AuthenticatedCaller caller) {
        return caller.isPrivileged() || job.getOwner().getId().equals(caller.userId());
    }
}
