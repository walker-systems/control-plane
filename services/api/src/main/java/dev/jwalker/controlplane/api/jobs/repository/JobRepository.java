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

    List<Job> findBySourceScheduleId(UUID sourceScheduleId);

    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT j FROM Job j LEFT JOIN FETCH j.owner WHERE j.id = :id")
    Optional<Job> findByIdWithRelations(@Param("id") UUID id);

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
    // executor invocations pick disjoint slices without blocking. Ordering
    // is priority-desc first (HIGH before LOW) then created_at-asc so
    // within a priority band it's FIFO. Same -2 hint trick as the
    // schedule repo for SKIP LOCKED semantics.
    //
    // The availableAt gate holds retry-delayed jobs out of the batch until
    // their backoff elapses; freshly created jobs default availableAt = now
    // so they're immediately eligible.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
    select j from Job j
    where j.status = dev.jwalker.controlplane.api.jobs.model.JobStatus.PENDING
      and j.availableAt <= :cutoff
    order by j.priority desc, j.createdAt asc
    """)
    List<Job> findPendingForUpdate(@Param("cutoff") OffsetDateTime cutoff, Pageable pageable);
}
