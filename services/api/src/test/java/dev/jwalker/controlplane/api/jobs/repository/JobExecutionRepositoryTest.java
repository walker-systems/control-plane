package dev.jwalker.controlplane.api.jobs.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobExecution;
import dev.jwalker.controlplane.api.jobs.model.JobExecutionStatus;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
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
class JobExecutionRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16");

    @Autowired
    private JobExecutionRepository jobExecutionRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private UserRepository userRepository;

    private Job savedJob() {
        User owner = userRepository.saveAndFlush(new User(null, "owner@example.com", "hash", UserStatus.ACTIVE));
        return jobRepository.saveAndFlush(
                new Job(null, owner, null, JobType.CUSTOMER_EXPORT, null, JobStatus.RUNNING, JobPriority.MEDIUM, null, 3));
    }

    private JobExecution savedExecution(Job job, int attemptNumber, JobExecutionStatus status) {
        return jobExecutionRepository.saveAndFlush(
                new JobExecution(null, job, "worker-1", attemptNumber, status));
    }

    @Test
    void findByJob_returnsExecutionsForJob() {
        Job job = savedJob();

        savedExecution(job, 1, JobExecutionStatus.FAILED);
        savedExecution(job, 2, JobExecutionStatus.RUNNING);

        List<JobExecution> result = jobExecutionRepository.findByJob(job);

        assertThat(result).hasSize(2)
                .allMatch(e -> e.getJob().getId().equals(job.getId()));
    }

    @Test
    void findByJobOrderByAttemptNumberAsc_returnsExecutionsInOrder() {
        Job job = savedJob();

        savedExecution(job, 3, JobExecutionStatus.FAILED);
        savedExecution(job, 1, JobExecutionStatus.FAILED);
        savedExecution(job, 2, JobExecutionStatus.FAILED);

        List<JobExecution> result = jobExecutionRepository.findByJobOrderByAttemptNumberAsc(job);

        assertThat(result).hasSize(3)
                .extracting(JobExecution::getAttemptNumber)
                .containsExactly(1, 2, 3);
    }

    @Test
    void findFirstByJobOrderByAttemptNumberDesc_returnsLatestAttempt() {
        Job job = savedJob();

        savedExecution(job, 1, JobExecutionStatus.FAILED);
        savedExecution(job, 2, JobExecutionStatus.FAILED);
        savedExecution(job, 3, JobExecutionStatus.RUNNING);

        Optional<JobExecution> result = jobExecutionRepository.findFirstByJobOrderByAttemptNumberDesc(job);

        assertThat(result).isPresent();
        assertThat(result.get().getAttemptNumber()).isEqualTo(3);
    }

    @Test
    void findFirstByJobOrderByAttemptNumberDesc_returnsEmpty_whenNoExecutions() {
        Job job = savedJob();

        Optional<JobExecution> result = jobExecutionRepository.findFirstByJobOrderByAttemptNumberDesc(job);

        assertThat(result).isEmpty();
    }

    @Test
    void findByStatus_returnsExecutionsWithGivenStatus() {
        Job job = savedJob();

        savedExecution(job, 1, JobExecutionStatus.RUNNING);
        savedExecution(job, 2, JobExecutionStatus.RUNNING);
        savedExecution(job, 3, JobExecutionStatus.SUCCEEDED);

        List<JobExecution> result = jobExecutionRepository.findByStatus(JobExecutionStatus.RUNNING);

        assertThat(result).hasSize(2)
                .allMatch(e -> e.getStatus() == JobExecutionStatus.RUNNING);
    }
}
