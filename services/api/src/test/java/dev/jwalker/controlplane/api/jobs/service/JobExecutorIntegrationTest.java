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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "app.security.jwt-secret=test-secret-must-be-at-least-32-bytes-long-xx",
        "app.security.access-token-minutes=15",
        "app.security.refresh-token-days=7",
        // Both ticks off — the tests drive the executor manually.
        "app.scheduling.enabled=false",
        "app.executor.enabled=false"
})
class JobExecutorIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired private JobExecutor executor;
    @Autowired private JobRepository jobRepository;
    @Autowired private JobExecutionRepository jobExecutionRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        auditEventRepository.deleteAll();
        // job_executions goes first — FK to jobs prevents wiping jobs otherwise.
        jobExecutionRepository.deleteAll();
        jobRepository.deleteAll();
        jdbcTemplate.execute("DELETE FROM job_schedules");
        userRepository.deleteAll();
        // Reset batchSize in case a prior test overrode it via reflection.
        ReflectionTestUtils.setField(executor, "batchSize", 10);
    }

    @Test
    void processPending_endToEnd_happyPath() {
        User owner = seedUser("alice@example.com");
        Job seeded = seedPendingJob(owner, JobPriority.MEDIUM);

        int processed = executor.processPending();

        assertThat(processed).isEqualTo(1);

        Job reloaded = jobRepository.findById(seeded.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.SUCCEEDED);

        List<JobExecution> executions = jobExecutionRepository.findByJob(reloaded);
        assertThat(executions).hasSize(1);
        JobExecution exec = executions.get(0);
        assertThat(exec.getStatus()).isEqualTo(JobExecutionStatus.SUCCEEDED);
        assertThat(exec.getAttemptNumber()).isEqualTo(1);
        assertThat(exec.getOutputSummary()).contains("handled by");

        List<AuditEvent> startedEvents =
                auditEventRepository.findByEventType(AuditEventType.JOB_STARTED);
        List<AuditEvent> succeededEvents =
                auditEventRepository.findByEventType(AuditEventType.JOB_SUCCEEDED);
        assertThat(startedEvents).hasSize(1);
        assertThat(succeededEvents).hasSize(1);
        assertThat(startedEvents.get(0).getActor()).isNull();
        assertThat(startedEvents.get(0).getTargetId()).isEqualTo(seeded.getId());
    }

    @Test
    void processPending_honorsPriorityOrdering() {
        User owner = seedUser("alice@example.com");
        // Seed in mixed order to prove ordering isn't just insertion order.
        Job medium = seedPendingJob(owner, JobPriority.MEDIUM);
        Job high = seedPendingJob(owner, JobPriority.HIGH);
        Job low = seedPendingJob(owner, JobPriority.LOW);

        // One job per tick so we can observe strict pickup order.
        ReflectionTestUtils.setField(executor, "batchSize", 1);

        executor.processPending();
        assertProcessed(high);
        assertNotProcessed(medium);
        assertNotProcessed(low);

        executor.processPending();
        assertProcessed(high);
        assertProcessed(medium);
        assertNotProcessed(low);

        executor.processPending();
        assertProcessed(high);
        assertProcessed(medium);
        assertProcessed(low);
    }

    @Test
    void processPending_skipsFutureAvailableAt() {
        User owner = seedUser("alice@example.com");
        Job seeded = seedPendingJob(owner, JobPriority.MEDIUM);
        seeded.setAvailableAt(OffsetDateTime.now().plusMinutes(1));
        jobRepository.saveAndFlush(seeded);

        int processed = executor.processPending();

        assertThat(processed).isZero();
        assertThat(jobRepository.findById(seeded.getId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.PENDING);
        assertThat(jobExecutionRepository.findAll()).isEmpty();
    }

    @Test
    void processPending_underParallelInvocation_processesEachJobOnce() throws Exception {
        User owner = seedUser("alice@example.com");
        for (int i = 0; i < 10; i++) {
            seedPendingJob(owner, JobPriority.MEDIUM);
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        Callable<Integer> task = () -> {
            startLatch.await();
            return executor.processPending();
        };
        Future<Integer> f1 = pool.submit(task);
        Future<Integer> f2 = pool.submit(task);
        startLatch.countDown();
        int total = f1.get() + f2.get();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // With batch size 10 and 10 jobs, one thread grabs all 10 first;
        // the other sees SKIP LOCKED and returns 0. Either way, total == 10.
        assertThat(total).isEqualTo(10);
        long succeeded = jobRepository.findAll().stream()
                .filter(j -> j.getStatus() == JobStatus.SUCCEEDED)
                .count();
        assertThat(succeeded).isEqualTo(10);
        assertThat(jobExecutionRepository.findAll()).hasSize(10);
    }

    private User seedUser(String email) {
        return userRepository.saveAndFlush(
                new User(null, email, "hash", UserStatus.ACTIVE));
    }

    private Job seedPendingJob(User owner, JobPriority priority) {
        Job job = new Job(
                null, owner, null, JobType.CRM_SYNC, "{\"k\":\"v\"}",
                JobStatus.PENDING, priority, null, 3);
        return jobRepository.saveAndFlush(job);
    }

    private void assertProcessed(Job job) {
        Job reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("job %s should be processed", job.getId())
                .isNotEqualTo(JobStatus.PENDING);
    }

    private void assertNotProcessed(Job job) {
        Job reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("job %s should not be processed yet", job.getId())
                .isEqualTo(JobStatus.PENDING);
    }
}
