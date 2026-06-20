package dev.jwalker.controlplane.api.jobs.repository;

import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.users.model.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
}
