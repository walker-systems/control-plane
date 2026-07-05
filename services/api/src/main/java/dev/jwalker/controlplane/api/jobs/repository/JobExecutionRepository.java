package dev.jwalker.controlplane.api.jobs.repository;

import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobExecution;
import dev.jwalker.controlplane.api.jobs.model.JobExecutionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobExecutionRepository extends JpaRepository<JobExecution, UUID> {

    List<JobExecution> findByJob(Job job);

    List<JobExecution> findByJobOrderByAttemptNumberAsc(Job job);

    Optional<JobExecution> findFirstByJobOrderByAttemptNumberDesc(Job job);

    List<JobExecution> findByStatus(JobExecutionStatus status);

    // Attempt lookup by job + attempt number — attempt numbers are unique
    // per job (uq_job_executions_job_attempt in V4), so this returns
    // at most one row.
    Optional<JobExecution> findByJob_IdAndAttemptNumber(UUID jobId, int attemptNumber);
}
