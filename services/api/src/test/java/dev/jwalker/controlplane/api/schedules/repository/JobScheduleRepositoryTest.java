package dev.jwalker.controlplane.api.schedules.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.schedules.model.JobSchedule;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JobScheduleRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16");

    @Autowired
    private JobScheduleRepository jobScheduleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicInteger nameCounter = new AtomicInteger();

    private User savedUser(String email) {
        return userRepository.saveAndFlush(new User(null, email, "hash", UserStatus.ACTIVE));
    }

    private JobSchedule savedSchedule(User owner, boolean enabled, OffsetDateTime nextRunAt) {
        JobSchedule schedule = new JobSchedule(
                null, owner, "sched-" + nameCounter.incrementAndGet(),
                JobType.CRM_SYNC, null, JobPriority.LOW, 0, "0 0 * * * *", "UTC");
        schedule.setEnabled(enabled);
        schedule.setNextRunAt(nextRunAt);
        return jobScheduleRepository.saveAndFlush(schedule);
    }

    @Test
    void findByOwner_returnsSchedulesForOwner() {
        User owner = savedUser("owner@example.com");
        User other = savedUser("other@example.com");

        savedSchedule(owner, true, null);
        savedSchedule(owner, true, null);
        savedSchedule(other, true, null);

        List<JobSchedule> result = jobScheduleRepository.findByOwner(owner);

        assertThat(result).hasSize(2)
                .allMatch(s -> s.getOwner().getId().equals(owner.getId()));
    }

    @Test
    void findByEnabled_returnsEnabledOrDisabledSchedules() {
        User owner = savedUser("owner@example.com");

        savedSchedule(owner, false, null);
        savedSchedule(owner, false, null);
        savedSchedule(owner, true, null);

        List<JobSchedule> disabled = jobScheduleRepository.findByEnabled(false);
        List<JobSchedule> enabled = jobScheduleRepository.findByEnabled(true);

        assertThat(disabled).hasSize(2).noneMatch(JobSchedule::isEnabled);
        assertThat(enabled).hasSize(1).allMatch(JobSchedule::isEnabled);
    }

    @Test
    void findDueSchedules_returnsEnabledSchedulesBeforeCutoff() {
        User owner = savedUser("owner@example.com");
        OffsetDateTime now = OffsetDateTime.now();

        savedSchedule(owner, true, now.minusHours(1));
        savedSchedule(owner, true, now.plusHours(1));
        savedSchedule(owner, false, now.minusHours(1));

        List<JobSchedule> result = jobScheduleRepository.findDueSchedules(now);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isEnabled()).isTrue();
        assertThat(result.get(0).getNextRunAt()).isBefore(now);
    }

    @Test
    void findDueSchedules_returnsEmpty_whenNoSchedulesDue() {
        User owner = savedUser("owner@example.com");
        OffsetDateTime now = OffsetDateTime.now();

        savedSchedule(owner, true, now.plusHours(1));

        List<JobSchedule> result = jobScheduleRepository.findDueSchedules(now);

        assertThat(result).isEmpty();
    }

    @Test
    void insert_withoutSpecifyingEnabled_defaultsToTrue() {
        User owner = savedUser("owner@example.com");

        // Insert via native SQL omitting `enabled` so the DB DEFAULT fires —
        // simulates a DBA script, future migration, or any code path that
        // bypasses the entity constructor. Without V9 this would default
        // to false because RENAME COLUMN preserved the old paused=FALSE
        // default after we flipped the semantics.
        jdbcTemplate.update("""
                INSERT INTO job_schedules
                    (id, owner_user_id, name, type, priority, max_retries,
                     cron_expression, timezone)
                VALUES
                    (gen_random_uuid(), ?, 'default-test', 'CRM_SYNC', 'MEDIUM',
                     3, '0 0 * * * *', 'UTC')
                """, owner.getId());

        Boolean enabled = jdbcTemplate.queryForObject(
                "SELECT enabled FROM job_schedules WHERE name = 'default-test'",
                Boolean.class);
        assertThat(enabled).isTrue();
    }

    @Test
    void delete_softDeletesByUpdatingDeletedAt() {
        User owner = savedUser("owner@example.com");
        JobSchedule s = savedSchedule(owner, true, null);

        jobScheduleRepository.delete(s);
        jobScheduleRepository.flush();

        // Default queries (with @SQLRestriction) should not return it
        assertThat(jobScheduleRepository.findById(s.getId())).isEmpty();
        assertThat(jobScheduleRepository.findByOwner(owner)).isEmpty();
    }
}
