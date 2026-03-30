package dev.jwalker.controlplane.api.jobs.repository;

import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobExecution;
import dev.jwalker.controlplane.api.jobs.model.JobExecutionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JobExecutionRepository extends JpaRepository<JobExecution, UUID> {

    List<JobExecution> findByJob(Job job);

    List<JobExecution> findByJobOrderByAttemptNumberAsc(Job job);

    Optional<JobExecution> findFirstByJobOrderByAttemptNumberDesc(Job job);

    List<JobExecution> findByStatus(JobExecutionStatus status);
}
