package dev.jwalker.controlplane.api.jobs.repository;

import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobExecution;
import dev.jwalker.controlplane.api.jobs.model.JobExecutionStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface JobExecutionRepository extends JpaRepository<JobExecution, UUID> {

    List<JobExecution> findByJob(Job job);

    List<JobExecution> findByJobOrderByAttemptNumberAsc(Job job);

    // Bounded variant of the ordered lookup. The executions endpoint uses
    // this with PageRequest.of(0, 100) so responses stay small even for
    // jobs that have been through many /retry cycles.
    List<JobExecution> findByJobOrderByAttemptNumberAsc(Job job, Pageable pageable);

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

    // Executions whose lease has expired — the worker that started them
    // is presumed dead. Locked FOR UPDATE SKIP LOCKED so parallel
    // watchdog ticks reclaim disjoint slices. Same -2 hint trick as the
    // executor and schedule repos. Ordering by leaseExpiresAt puts the
    // most overdue rows first so the oldest failures get attention
    // even if the batch cap truncates the tail.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT je FROM JobExecution je
            LEFT JOIN FETCH je.job
            WHERE je.status = dev.jwalker.controlplane.api.jobs.model.JobExecutionStatus.RUNNING
              AND je.leaseExpiresAt < :cutoff
            ORDER BY je.leaseExpiresAt
            """)
    List<JobExecution> findExpiredForUpdate(
            @Param("cutoff") OffsetDateTime cutoff, Pageable pageable);

    // Row-locked lookup by id. The executor's complete phase calls this
    // first (before locking the parent Job) so its lock order matches
    // the watchdog's exec-then-job ordering. Blocking, not SKIP LOCKED —
    // if the watchdog is currently reclaiming this row we want to wait
    // for it to finish and then see the updated status, not skip past.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT je FROM JobExecution je WHERE je.id = :id")
    Optional<JobExecution> findByIdForUpdate(@Param("id") UUID id);
}
