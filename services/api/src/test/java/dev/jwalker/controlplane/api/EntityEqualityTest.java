package dev.jwalker.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jwalker.controlplane.api.audit.model.AuditEvent;
import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.auth.model.RefreshToken;
import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobExecution;
import dev.jwalker.controlplane.api.jobs.model.JobExecutionStatus;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.schedules.model.JobSchedule;
import dev.jwalker.controlplane.api.users.model.Role;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntityEqualityTest {

    @Test
    void unsavedEntitiesOnlyEqualThemselves() {
        Object[][] pairs = {
                {auditEvent(null), auditEvent(null)},
                {refreshToken(null), refreshToken(null)},
                {job(null), job(null)},
                {jobExecution(null), jobExecution(null)},
                {jobSchedule(null), jobSchedule(null)},
                {role(null), role(null)},
                {user(null), user(null)}
        };

        for (Object[] pair : pairs) {
            assertThat(pair[0]).isEqualTo(pair[0]);
            assertThat(pair[0]).isNotEqualTo(pair[1]);
        }
    }

    @Test
    void savedEntitiesCompareById() {
        assertEqualById(auditEvent(UUID.randomUUID()), AuditEvent.class);
        assertEqualById(refreshToken(UUID.randomUUID()), RefreshToken.class);
        assertEqualById(job(UUID.randomUUID()), Job.class);
        assertEqualById(jobExecution(UUID.randomUUID()), JobExecution.class);
        assertEqualById(jobSchedule(UUID.randomUUID()), JobSchedule.class);
        assertEqualById(role(UUID.randomUUID()), Role.class);
        assertEqualById(user(UUID.randomUUID()), User.class);
    }

    private static <T> void assertEqualById(T entity, Class<T> entityClass) {
        T other = switch (entityClass.getSimpleName()) {
            case "AuditEvent" -> entityClass.cast(auditEvent(((AuditEvent) entity).getId()));
            case "RefreshToken" -> entityClass.cast(refreshToken(((RefreshToken) entity).getId()));
            case "Job" -> entityClass.cast(job(((Job) entity).getId()));
            case "JobExecution" -> entityClass.cast(jobExecution(((JobExecution) entity).getId()));
            case "JobSchedule" -> entityClass.cast(jobSchedule(((JobSchedule) entity).getId()));
            case "Role" -> entityClass.cast(role(((Role) entity).getId()));
            case "User" -> entityClass.cast(user(((User) entity).getId()));
            default -> throw new IllegalArgumentException("Unsupported entity: " + entityClass);
        };

        assertThat(entity).isEqualTo(other);
        assertThat(entity).hasSameHashCodeAs(other);
    }

    private static AuditEvent auditEvent(UUID id) {
        return new AuditEvent(
                id,
                user(UUID.randomUUID()),
                AuditEventType.LOGIN_SUCCEEDED,
                "USER",
                UUID.randomUUID(),
                "{}",
                "127.0.0.1",
                "test"
        );
    }

    private static RefreshToken refreshToken(UUID id) {
        return new RefreshToken(id, user(UUID.randomUUID()), "hash", OffsetDateTime.now().plusDays(1));
    }

    private static Job job(UUID id) {
        return new Job(
                id,
                user(UUID.randomUUID()),
                null,
                JobType.CRM_SYNC,
                "{}",
                JobStatus.PENDING,
                JobPriority.MEDIUM,
                null,
                3
        );
    }

    private static JobExecution jobExecution(UUID id) {
        return new JobExecution(id, job(UUID.randomUUID()), null, 1, JobExecutionStatus.PENDING);
    }

    private static JobSchedule jobSchedule(UUID id) {
        return new JobSchedule(
                id,
                user(UUID.randomUUID()),
                JobType.CRM_SYNC,
                "{}",
                JobPriority.MEDIUM,
                3,
                "0 * * * *",
                "UTC"
        );
    }

    private static Role role(UUID id) {
        return new Role(id, "USER");
    }

    private static User user(UUID id) {
        return new User(id, "user-" + UUID.randomUUID() + "@example.com", "hash", UserStatus.ACTIVE);
    }
}
