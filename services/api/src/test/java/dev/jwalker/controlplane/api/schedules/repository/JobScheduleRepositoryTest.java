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
class JobScheduleRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16");

    @Autowired
    private JobScheduleRepository jobScheduleRepository;

    @Autowired
    private UserRepository userRepository;

    private User savedUser(String email) {
        return userRepository.saveAndFlush(new User(null, email, "hash", UserStatus.ACTIVE));
    }

    private JobSchedule savedSchedule(User owner, boolean paused, OffsetDateTime nextRunAt) {
        JobSchedule schedule = new JobSchedule(null, owner, JobType.CRM_SYNC, null, JobPriority.LOW, 0, "0 * * * *", "UTC");
        schedule.setPaused(paused);
        schedule.setNextRunAt(nextRunAt);
        return jobScheduleRepository.saveAndFlush(schedule);
    }

    @Test
    void findByOwner_returnsSchedulesForOwner() {
        User owner = savedUser("owner@example.com");
        User other = savedUser("other@example.com");

        savedSchedule(owner, false, null);
        savedSchedule(owner, false, null);
        savedSchedule(other, false, null);

        List<JobSchedule> result = jobScheduleRepository.findByOwner(owner);

        assertThat(result).hasSize(2)
                .allMatch(s -> s.getOwner().getId().equals(owner.getId()));
    }

    @Test
    void findByPaused_returnsPausedSchedules() {
        User owner = savedUser("owner@example.com");

        savedSchedule(owner, true, null);
        savedSchedule(owner, true, null);
        savedSchedule(owner, false, null);

        List<JobSchedule> paused = jobScheduleRepository.findByPaused(true);
        List<JobSchedule> active = jobScheduleRepository.findByPaused(false);

        assertThat(paused).hasSize(2).allMatch(JobSchedule::isPaused);
        assertThat(active).hasSize(1).noneMatch(JobSchedule::isPaused);
    }

    @Test
    void findDueSchedules_returnsUnpausedSchedulesBeforeCutoff() {
        User owner = savedUser("owner@example.com");
        OffsetDateTime now = OffsetDateTime.now();

        savedSchedule(owner, false, now.minusHours(1));
        savedSchedule(owner, false, now.plusHours(1));
        savedSchedule(owner, true, now.minusHours(1));

        List<JobSchedule> result = jobScheduleRepository.findDueSchedules(now);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isPaused()).isFalse();
        assertThat(result.get(0).getNextRunAt()).isBefore(now);
    }

    @Test
    void findDueSchedules_returnsEmpty_whenNoSchedulesDue() {
        User owner = savedUser("owner@example.com");
        OffsetDateTime now = OffsetDateTime.now();

        savedSchedule(owner, false, now.plusHours(1));

        List<JobSchedule> result = jobScheduleRepository.findDueSchedules(now);

        assertThat(result).isEmpty();
    }
}
