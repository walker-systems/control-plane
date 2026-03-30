package dev.jwalker.controlplane.api.schedules.repository;

import dev.jwalker.controlplane.api.schedules.model.JobSchedule;
import dev.jwalker.controlplane.api.users.model.User;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JobScheduleRepository extends JpaRepository<JobSchedule, UUID> {

    List<JobSchedule> findByOwner(User owner);

    List<JobSchedule> findByPaused(boolean paused);

    @Query("""
    select js
    from JobSchedule js
    where js.paused = false
      and js.nextRunAt < :cutoff
    """)
    List<JobSchedule> findDueSchedules(OffsetDateTime cutoff);
}
