package dev.jwalker.controlplane.api.jobs.repository;

import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobExecution;
import dev.jwalker.controlplane.api.jobs.model.JobExecutionStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobExecutionRepository extends JpaRepository<JobExecution, UUID> {

    List<JobExecution> findByJob(Job job);

    List<JobExecution> findByJobOrderByAttemptNumberAsc(Job job);

    Optional<JobExecution> findFirstByJobOrderByAttemptNumberDesc(Job job);

    List<JobExecution> findByStatus(JobExecutionStatus status);

    // Attempt lookup by job + attempt number — attempt numbers are unique
    // per job (uq_job_executions_job_attempt in V4), so this returns
    // at most one row.
    Optional<JobExecution> findByJob_IdAndAttemptNumber(UUID jobId, int attemptNumber);

    // Single-job attempt count for endpoints that already have the id.
    long countByJob_Id(UUID jobId);

    // Bulk count for list endpoints — one query returns the count per job
    // id, avoiding N+1 queries when mapping a page of jobs to responses.
    // Missing entries mean zero executions.
    @Query("""
            SELECT je.job.id AS jobId, COUNT(je) AS attemptCount
            FROM JobExecution je
            WHERE je.job.id IN :jobIds
            GROUP BY je.job.id
            """)
    List<JobAttemptCount> countAttemptsByJobIds(@Param("jobIds") Collection<UUID> jobIds);

    // Spring Data projection for the grouped-count query. Interface fields
    // map to the SELECT aliases by name.
    interface JobAttemptCount {
        UUID getJobId();
        long getAttemptCount();
    }
}
