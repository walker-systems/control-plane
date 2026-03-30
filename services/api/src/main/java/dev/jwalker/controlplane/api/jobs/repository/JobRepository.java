package dev.jwalker.controlplane.api.jobs.repository;

import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.schedules.model.JobSchedule;
import dev.jwalker.controlplane.api.users.model.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByOwner(User owner);

    List<Job> findByStatus(JobStatus status);

    List<Job> findByOwnerAndStatus(User owner, JobStatus status);

    List<Job> findBySourceSchedule(JobSchedule sourceSchedule);

    Optional<Job> findByIdempotencyKey(String idempotencyKey);
}
