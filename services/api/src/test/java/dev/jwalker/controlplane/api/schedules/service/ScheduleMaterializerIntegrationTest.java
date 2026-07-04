package dev.jwalker.controlplane.api.schedules.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jwalker.controlplane.api.audit.model.AuditEvent;
import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.repository.AuditEventRepository;
import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.jobs.repository.JobRepository;
import dev.jwalker.controlplane.api.schedules.model.JobSchedule;
import dev.jwalker.controlplane.api.schedules.repository.JobScheduleRepository;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "app.security.jwt-secret=test-secret-must-be-at-least-32-bytes-long-xx",
        "app.security.access-token-minutes=15",
        "app.security.refresh-token-days=7",
        "app.scheduling.enabled=false"
})
class ScheduleMaterializerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired private ScheduleMaterializer materializer;
    @Autowired private JobScheduleRepository jobScheduleRepository;
    @Autowired private JobRepository jobRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        auditEventRepository.deleteAll();
        jobRepository.deleteAll();
        // Raw DELETE bypasses JobSchedule's @SQLDelete/@SQLRestriction so rows from
        // prior tests (soft-deleted or not) don't linger and trip owner-name uniqueness.
        jdbcTemplate.execute("DELETE FROM job_schedules");
        userRepository.deleteAll();
    }

    @Test
    void materializeDue_createsJobAndAdvancesSchedule() {
        User owner = seedUser("alice@example.com");
        JobSchedule seeded = seedDueSchedule(owner, "0 * * * * *", Duration.ofSeconds(5));
        OffsetDateTime seededNextRunAt = seeded.getNextRunAt();

        int fired = materializer.materializeDue();

        assertThat(fired).isEqualTo(1);
        List<Job> jobs = jobRepository.findAll();
        assertThat(jobs).hasSize(1);
        Job job = jobs.get(0);
        assertThat(job.getSourceScheduleId()).isEqualTo(seeded.getId());
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getType()).isEqualTo(seeded.getType());
        assertThat(job.getPriority()).isEqualTo(seeded.getPriority());

        JobSchedule reloaded = jobScheduleRepository.findById(seeded.getId()).orElseThrow();
        assertThat(reloaded.getNextRunAt()).isAfter(seededNextRunAt);
        assertThat(reloaded.getLastEnqueuedAt()).isNotNull();
    }

    @Test
    void materializeDue_emitsScheduleFiredAudit() {
        User owner = seedUser("alice@example.com");
        JobSchedule seeded = seedDueSchedule(owner, "0 * * * * *", Duration.ofSeconds(5));

        materializer.materializeDue();

        List<AuditEvent> events = auditEventRepository.findByEventType(AuditEventType.SCHEDULE_FIRED);
        assertThat(events).hasSize(1);
        AuditEvent event = events.get(0);
        assertThat(event.getActor()).isNull();
        assertThat(event.getTargetType()).isEqualTo("JobSchedule");
        assertThat(event.getTargetId()).isEqualTo(seeded.getId());
        assertThat(event.getMetadataJson()).contains("jobId").contains("firedAt");
    }

    @Test
    void materializeDue_skipsDisabledSchedules() {
        User owner = seedUser("alice@example.com");
        JobSchedule enabled = seedDueSchedule(owner, "0 * * * * *", Duration.ofSeconds(5));
        JobSchedule disabled = seedDueSchedule(owner, "0 * * * * *", Duration.ofSeconds(5));
        disabled.disable();
        jobScheduleRepository.saveAndFlush(disabled);

        materializer.materializeDue();

        assertThat(jobRepository.findBySourceScheduleId(enabled.getId())).hasSize(1);
        assertThat(jobRepository.findBySourceScheduleId(disabled.getId())).isEmpty();
    }

    @Test
    void materializeDue_skipsSchedulesNotYetDue() {
        User owner = seedUser("alice@example.com");
        // negative "ago" duration → nextRunAt in the future
        seedDueSchedule(owner, "0 * * * * *", Duration.ofSeconds(-60));

        materializer.materializeDue();

        assertThat(jobRepository.findAll()).isEmpty();
    }

    @Test
    void materializeDue_skipsSchedulesWithNullNextRunAt() {
        User owner = seedUser("alice@example.com");
        JobSchedule idle = new JobSchedule(
                null, owner, "idle-" + UUID.randomUUID(), JobType.CRM_SYNC,
                null, JobPriority.MEDIUM, 3, "0 * * * * *", "UTC");
        idle.setNextRunAt(null);
        jobScheduleRepository.saveAndFlush(idle);

        materializer.materializeDue();

        assertThat(jobRepository.findAll()).isEmpty();
    }

    @Test
    void materializeDue_handlesMultipleSchedulesInOneTick() {
        User owner = seedUser("alice@example.com");
        seedDueSchedule(owner, "0 * * * * *", Duration.ofSeconds(1));
        seedDueSchedule(owner, "0 * * * * *", Duration.ofSeconds(2));
        seedDueSchedule(owner, "0 * * * * *", Duration.ofSeconds(3));

        int fired = materializer.materializeDue();

        assertThat(fired).isEqualTo(3);
        assertThat(jobRepository.findAll()).hasSize(3);
    }

    @Test
    void materializeDue_underParallelInvocation_neverDoubleFires() throws Exception {
        User owner = seedUser("alice@example.com");
        seedDueSchedule(owner, "0 * * * * *", Duration.ofSeconds(1));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        Callable<Integer> task = () -> {
            startLatch.await();
            return materializer.materializeDue();
        };
        Future<Integer> f1 = pool.submit(task);
        Future<Integer> f2 = pool.submit(task);
        startLatch.countDown();
        int total = f1.get() + f2.get();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // Regardless of whether the loser's SELECT hit SKIP LOCKED or ran after
        // the winner's commit (nextRunAt now advanced past `now`), only one job
        // ever gets created for the seeded due schedule.
        assertThat(total).isEqualTo(1);
        assertThat(jobRepository.findAll()).hasSize(1);
    }

    private User seedUser(String email) {
        User u = new User(null, email, "hash", UserStatus.ACTIVE);
        return userRepository.saveAndFlush(u);
    }

    private JobSchedule seedDueSchedule(User owner, String cron, Duration ago) {
        JobSchedule s = new JobSchedule(
                null,
                owner,
                "sched-" + UUID.randomUUID(),
                JobType.CRM_SYNC,
                "{\"k\":\"v\"}",
                JobPriority.MEDIUM,
                3,
                cron,
                "UTC");
        s.setNextRunAt(OffsetDateTime.now().minus(ago));
        return jobScheduleRepository.saveAndFlush(s);
    }
}
