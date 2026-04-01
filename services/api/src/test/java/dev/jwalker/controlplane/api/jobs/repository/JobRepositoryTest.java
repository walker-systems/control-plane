package dev.jwalker.controlplane.api.jobs.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.schedules.model.JobSchedule;
import dev.jwalker.controlplane.api.schedules.repository.JobScheduleRepository;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JobRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16");

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JobScheduleRepository jobScheduleRepository;

    private User savedUser(String email) {
        return userRepository.saveAndFlush(new User(null, email, "hash", UserStatus.ACTIVE));
    }

    private Job savedJob(User owner, JobStatus status, String idempotencyKey) {
        Job job = new Job(null, owner, null, JobType.CUSTOMER_EXPORT, null, status, JobPriority.MEDIUM, idempotencyKey, 3);
        return jobRepository.saveAndFlush(job);
    }

    @Test
    void findByOwner_returnsJobsForOwner() {
        User owner = savedUser("owner@example.com");
        User other = savedUser("other@example.com");

        savedJob(owner, JobStatus.PENDING, null);
        savedJob(owner, JobStatus.RUNNING, null);
        savedJob(other, JobStatus.PENDING, null);

        List<Job> result = jobRepository.findByOwner(owner);

        assertThat(result).hasSize(2)
                .allMatch(j -> j.getOwner().getId().equals(owner.getId()));
    }

    @Test
    void findByStatus_returnsJobsWithGivenStatus() {
        User owner = savedUser("owner@example.com");

        savedJob(owner, JobStatus.PENDING, null);
        savedJob(owner, JobStatus.PENDING, null);
        savedJob(owner, JobStatus.SUCCEEDED, null);

        List<Job> result = jobRepository.findByStatus(JobStatus.PENDING);

        assertThat(result).hasSize(2)
                .allMatch(j -> j.getStatus() == JobStatus.PENDING);
    }

    @Test
    void findByOwnerAndStatus_returnsMatchingJobs() {
        User owner = savedUser("owner@example.com");
        User other = savedUser("other@example.com");

        savedJob(owner, JobStatus.PENDING, null);
        savedJob(owner, JobStatus.SUCCEEDED, null);
        savedJob(other, JobStatus.PENDING, null);

        List<Job> result = jobRepository.findByOwnerAndStatus(owner, JobStatus.PENDING);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOwner().getId()).isEqualTo(owner.getId());
        assertThat(result.get(0).getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void findBySourceSchedule_returnsJobsLinkedToSchedule() {
        User owner = savedUser("owner@example.com");
        JobSchedule schedule = jobScheduleRepository.saveAndFlush(
                new JobSchedule(null, owner, JobType.CRM_SYNC, null, JobPriority.LOW, 0, "0 * * * *", "UTC"));

        Job scheduledJob = new Job(null, owner, schedule, JobType.CRM_SYNC, null, JobStatus.PENDING, JobPriority.LOW, null, 0);
        jobRepository.saveAndFlush(scheduledJob);
        savedJob(owner, JobStatus.PENDING, null);

        List<Job> result = jobRepository.findBySourceSchedule(schedule);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSourceSchedule().getId()).isEqualTo(schedule.getId());
    }

    @Test
    void findByIdempotencyKey_returnsSavedJob() {
        User owner = savedUser("owner@example.com");

        Job saved = savedJob(owner, JobStatus.PENDING, "unique-key-123");

        Optional<Job> result = jobRepository.findByIdempotencyKey("unique-key-123");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
        assertThat(result.get().getIdempotencyKey()).isEqualTo("unique-key-123");
    }

    @Test
    void findByIdempotencyKey_returnsEmpty_whenNotFound() {
        Optional<Job> result = jobRepository.findByIdempotencyKey("nonexistent-key");

        assertThat(result).isEmpty();
    }
}
