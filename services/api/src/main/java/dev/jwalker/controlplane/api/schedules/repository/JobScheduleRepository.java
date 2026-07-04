package dev.jwalker.controlplane.api.schedules.repository;

import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.schedules.model.JobSchedule;
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

public interface JobScheduleRepository extends JpaRepository<JobSchedule, UUID> {

    List<JobSchedule> findByOwner(User owner);

    List<JobSchedule> findByEnabled(boolean enabled);

    @Query("""
    select js
    from JobSchedule js
    where js.enabled = true
      and js.nextRunAt < :cutoff
    """)
    List<JobSchedule> findDueSchedules(OffsetDateTime cutoff);

    // Locks each returned schedule with FOR UPDATE SKIP LOCKED so parallel
    // tick invocations across multiple API instances process disjoint slices
    // without blocking. Hibernate's lock-timeout hint value of -2 maps to
    // SKIP LOCKED on Postgres.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
    select s
    from JobSchedule s
    where s.enabled = true
      and s.nextRunAt is not null
      and s.nextRunAt <= :cutoff
    order by s.nextRunAt
    """)
    List<JobSchedule> findDueForUpdate(@Param("cutoff") OffsetDateTime cutoff, Pageable pageable);

    @Query("SELECT s FROM JobSchedule s LEFT JOIN FETCH s.owner WHERE s.id = :id")
    Optional<JobSchedule> findByIdWithOwner(@Param("id") UUID id);

    @Query(
      value = """
              SELECT s FROM JobSchedule s
              LEFT JOIN FETCH s.owner
              WHERE (:enabled IS NULL OR s.enabled = :enabled)
                AND (:type IS NULL OR s.type = :type)
                AND (:priority IS NULL OR s.priority = :priority)
                AND (:ownerId IS NULL OR s.owner.id = :ownerId)
              """,
      countQuery = """
              SELECT COUNT(s) FROM JobSchedule s
              WHERE (:enabled IS NULL OR s.enabled = :enabled)
                AND (:type IS NULL OR s.type = :type)
                AND (:priority IS NULL OR s.priority = :priority)
                AND (:ownerId IS NULL OR s.owner.id = :ownerId)
              """)
    Page<JobSchedule> search(
            @Param("enabled") Boolean enabled,
            @Param("type") JobType type,
            @Param("priority") JobPriority priority,
            @Param("ownerId") UUID ownerId,
            Pageable pageable);
}
