package dev.jwalker.controlplane.api.schedules.repository;

import dev.jwalker.controlplane.api.schedules.model.JobSchedule;
import dev.jwalker.controlplane.api.users.model.User;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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

    @Query("SELECT s FROM JobSchedule s LEFT JOIN FETCH s.owner WHERE s.id = :id")
    Optional<JobSchedule> findByIdWithOwner(@Param("id") UUID id);
}
