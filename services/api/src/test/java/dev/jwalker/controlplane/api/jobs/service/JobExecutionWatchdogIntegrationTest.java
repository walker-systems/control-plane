package dev.jwalker.controlplane.api.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jwalker.controlplane.api.audit.model.AuditEvent;
import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.repository.AuditEventRepository;
import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobExecution;
import dev.jwalker.controlplane.api.jobs.model.JobExecutionStatus;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.jobs.repository.JobExecutionRepository;
import dev.jwalker.controlplane.api.jobs.repository.JobRepository;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "app.security.jwt-secret=test-secret-must-be-at-least-32-bytes-long-xx",
        "app.security.access-token-minutes=15",
        "app.security.refresh-token-days=7",
        // All background ticks off — we drive the watchdog manually.
        "app.scheduling.enabled=false",
        "app.executor.enabled=false",
        "app.watchdog.enabled=false"
})
class JobExecutionWatchdogIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired private JobExecutionWatchdog watchdog;
    @Autowired private JobRepository jobRepository;
    @Autowired private JobExecutionRepository jobExecutionRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        auditEventRepository.deleteAll();
        jobExecutionRepository.deleteAll();
        jobRepository.deleteAll();
        jdbcTemplate.execute("DELETE FROM job_schedules");
        userRepository.deleteAll();
    }

    @Test
    void reclaimExpired_marksExecutionTimedOutAndRequeuesJob() {
        User owner = seedUser("alice@example.com");
        Job job = seedRunningJob(owner, 3);
        JobExecution seeded = seedExpiredExecution(job, 1);

        int reclaimed = watchdog.reclaimExpired();

        assertThat(reclaimed).isEqualTo(1);

        JobExecution reloadedExec = jobExecutionRepository.findById(seeded.getId()).orElseThrow();
        assertThat(reloadedExec.getStatus()).isEqualTo(JobExecutionStatus.TIMED_OUT);
        assertThat(reloadedExec.getFinishedAt()).isNotNull();
        assertThat(reloadedExec.getErrorMessage()).contains("Lease expired");

        Job reloadedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloadedJob.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(reloadedJob.getAvailableAt()).isAfter(OffsetDateTime.now());
    }

    @Test
    void reclaimExpired_deadLettersJob_whenRetriesExhausted() {
        User owner = seedUser("alice@example.com");
        Job job = seedRunningJob(owner, 3);
        // attemptNumber 4 > maxRetries 3 → DEAD_LETTER path
        seedExpiredExecution(job, 4);

        watchdog.reclaimExpired();

        Job reloadedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloadedJob.getStatus()).isEqualTo(JobStatus.DEAD_LETTER);

        List<AuditEvent> timedOut =
                auditEventRepository.findByEventType(AuditEventType.JOB_TIMED_OUT);
        List<AuditEvent> deadLettered =
                auditEventRepository.findByEventType(AuditEventType.JOB_DEAD_LETTERED);
        assertThat(timedOut).hasSize(1);
        assertThat(deadLettered).hasSize(1);
    }

    @Test
    void reclaimExpired_skipsExecutionsThatAreNotYetExpired() {
        User owner = seedUser("alice@example.com");
        Job job = seedRunningJob(owner, 3);

        JobExecution exec = new JobExecution(
                null, job, "worker-alive", 1, JobExecutionStatus.RUNNING);
        exec.setStartedAt(OffsetDateTime.now());
        // Lease in the future — not yet expired.
        exec.setLeaseExpiresAt(OffsetDateTime.now().plusMinutes(5));
        jobExecutionRepository.saveAndFlush(exec);

        int reclaimed = watchdog.reclaimExpired();

        assertThat(reclaimed).isZero();
        JobExecution reloaded = jobExecutionRepository.findById(exec.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobExecutionStatus.RUNNING);
    }

    @Test
    void reclaimExpired_skipsExecutionsThatAlreadyTerminated() {
        User owner = seedUser("alice@example.com");
        Job job = seedRunningJob(owner, 3);

        JobExecution exec = new JobExecution(
                null, job, "worker-1", 1, JobExecutionStatus.SUCCEEDED);
        exec.setStartedAt(OffsetDateTime.now().minusMinutes(10));
        exec.setFinishedAt(OffsetDateTime.now().minusMinutes(5));
        // Lease is expired but the execution is already terminal.
        exec.setLeaseExpiresAt(OffsetDateTime.now().minusMinutes(6));
        jobExecutionRepository.saveAndFlush(exec);

        int reclaimed = watchdog.reclaimExpired();

        assertThat(reclaimed).isZero();
    }

    private User seedUser(String email) {
        return userRepository.saveAndFlush(
                new User(null, email, "hash", UserStatus.ACTIVE));
    }

    private Job seedRunningJob(User owner, int maxRetries) {
        Job job = new Job(
                null, owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.RUNNING, JobPriority.MEDIUM, null, maxRetries);
        return jobRepository.saveAndFlush(job);
    }

    private JobExecution seedExpiredExecution(Job job, int attemptNumber) {
        JobExecution exec = new JobExecution(
                null, job, "worker-dead", attemptNumber, JobExecutionStatus.RUNNING);
        exec.setStartedAt(OffsetDateTime.now().minusMinutes(10));
        // Lease expired 1 minute ago.
        exec.setLeaseExpiresAt(OffsetDateTime.now().minusMinutes(1));
        return jobExecutionRepository.saveAndFlush(exec);
    }
}
