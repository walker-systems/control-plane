package dev.jwalker.controlplane.api.jobs.repository;

import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.users.model.User;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByOwner(User owner);

    List<Job> findByStatus(JobStatus status);

    List<Job> findByOwnerAndStatus(User owner, JobStatus status);

    // One-row-per-status counts used by GET /api/jobs/stats. Passing
    // ownerId = null (privileged callers) removes the owner filter and
    // aggregates across all jobs. Statuses with zero jobs won't appear
    // in the result — the service fills those in with 0 so every
    // JobStatus is represented in the response.
    @Query("""
            SELECT j.status AS status, COUNT(j) AS count
            FROM Job j
            WHERE (:ownerId IS NULL OR j.owner.id = :ownerId)
            GROUP BY j.status
            """)
    List<JobStatusCount> countByStatus(@Param("ownerId") UUID ownerId);

    // Spring Data projection for the grouped-count query above.
    interface JobStatusCount {
        JobStatus getStatus();
        long getCount();
    }

    List<Job> findBySourceScheduleId(UUID sourceScheduleId);

    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT j FROM Job j LEFT JOIN FETCH j.owner WHERE j.id = :id")
    Optional<Job> findByIdWithRelations(@Param("id") UUID id);

    // Locking read for cancel — waits for any concurrent executor
    // transaction on this row to commit before returning. Without this,
    // cancel's non-locking read could see a stale PENDING status while
    // the executor is mid-processing, then blindly overwrite the
    // executor's committed SUCCEEDED / retry / DEAD_LETTER outcome.
    // No skip-locked hint here: we WANT to wait, not skip.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM Job j LEFT JOIN FETCH j.owner WHERE j.id = :id")
    Optional<Job> findByIdWithRelationsForUpdate(@Param("id") UUID id);

    @Query(
            value = """
                    SELECT j FROM Job j
                    LEFT JOIN FETCH j.owner
                    WHERE (:status IS NULL OR j.status = :status)
                      AND (:type IS NULL OR j.type = :type)
                      AND (:priority IS NULL OR j.priority = :priority)
                      AND (:ownerId IS NULL OR j.owner.id = :ownerId)
                      AND (:sourceScheduleId IS NULL OR j.sourceScheduleId = :sourceScheduleId)
                    """,
            countQuery = """
                    SELECT COUNT(j) FROM Job j
                    WHERE (:status IS NULL OR j.status = :status)
                      AND (:type IS NULL OR j.type = :type)
                      AND (:priority IS NULL OR j.priority = :priority)
                      AND (:ownerId IS NULL OR j.owner.id = :ownerId)
                      AND (:sourceScheduleId IS NULL OR j.sourceScheduleId = :sourceScheduleId)
                    """)
    Page<Job> search(
            @Param("status") JobStatus status,
            @Param("type") JobType type,
            @Param("priority") JobPriority priority,
            @Param("ownerId") UUID ownerId,
            @Param("sourceScheduleId") UUID sourceScheduleId,
            Pageable pageable);

    // Locks each returned job with FOR UPDATE SKIP LOCKED so parallel
    // executor invocations pick disjoint slices without blocking. Same -2
    // hint trick as the schedule repo for SKIP LOCKED semantics.
    //
    // No LEFT JOIN FETCH here: Hibernate's follow-on locking with fetch
    // joins conflicts with SKIP LOCKED under concurrent access (throws
    // "Expecting results" when a peer grabs the row first). Handlers that
    // need lazy fields (owner) must be loaded explicitly by the executor's
    // pick phase.
    //
    // Ordering: HIGH before MEDIUM before LOW, then FIFO by createdAt within
    // a priority. Priority is stored as VARCHAR (@Enumerated STRING) so a
    // naive `order by priority desc` would sort alphabetically — MEDIUM,
    // LOW, HIGH — not by logical priority. The CASE forces a real ordering.
    //
    // The availableAt gate holds retry-delayed jobs out of the batch until
    // their backoff elapses; freshly created jobs default availableAt = now
    // so they're immediately eligible.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
    select j from Job j
    where j.status = JobStatus.PENDING
      and j.availableAt <= :cutoff
    order by
        case j.priority
            when JobPriority.HIGH then 3
            when JobPriority.MEDIUM then 2
            else 1
        end desc,
        j.createdAt asc
    """)
    List<Job> findPendingForUpdate(@Param("cutoff") OffsetDateTime cutoff, Pageable pageable);
}
