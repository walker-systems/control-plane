package dev.jwalker.controlplane.api.audit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jwalker.controlplane.api.audit.model.AuditEvent;
import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
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
class AuditEventRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16");

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private UserRepository userRepository;

    private User savedUser(String email) {
        return userRepository.saveAndFlush(new User(null, email, "hash", UserStatus.ACTIVE));
    }

    private AuditEvent savedEvent(User actor, AuditEventType type, String targetType, UUID targetId) {
        return auditEventRepository.saveAndFlush(
                new AuditEvent(null, actor, type, targetType, targetId, null, "127.0.0.1", "TestAgent/1.0"));
    }

    @Test
    void findByActor_returnsEventsForActor() {
        User actor = savedUser("actor@example.com");
        User other = savedUser("other@example.com");

        savedEvent(actor, AuditEventType.LOGIN_SUCCEEDED, null, null);
        savedEvent(actor, AuditEventType.LOGOUT, null, null);
        savedEvent(other, AuditEventType.LOGIN_SUCCEEDED, null, null);

        List<AuditEvent> result = auditEventRepository.findByActor(actor);

        assertThat(result).hasSize(2)
                .allMatch(e -> e.getActor().getId().equals(actor.getId()));
    }

    @Test
    void findByEventType_returnsEventsWithGivenType() {
        User actor = savedUser("actor@example.com");

        savedEvent(actor, AuditEventType.LOGIN_SUCCEEDED, null, null);
        savedEvent(actor, AuditEventType.LOGIN_SUCCEEDED, null, null);
        savedEvent(actor, AuditEventType.LOGOUT, null, null);

        List<AuditEvent> result = auditEventRepository.findByEventType(AuditEventType.LOGIN_SUCCEEDED);

        assertThat(result).hasSize(2)
                .allMatch(e -> e.getEventType() == AuditEventType.LOGIN_SUCCEEDED);
    }

    @Test
    void findByCreatedAtAfter_returnsEventsAfterCutoff() {
        User actor = savedUser("actor@example.com");
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(1);

        savedEvent(actor, AuditEventType.JOB_CREATED, null, null);

        List<AuditEvent> result = auditEventRepository.findByCreatedAtAfter(cutoff);

        assertThat(result).isNotEmpty()
                .allMatch(e -> e.getCreatedAt().isAfter(cutoff));
    }

    @Test
    void findByCreatedAtAfter_returnsEmpty_whenNoEventsAfterCutoff() {
        OffsetDateTime futureCutoff = OffsetDateTime.now().plusDays(1);

        List<AuditEvent> result = auditEventRepository.findByCreatedAtAfter(futureCutoff);

        assertThat(result).isEmpty();
    }

    @Test
    void findByTargetTypeAndTargetId_returnsMatchingEvents() {
        User actor = savedUser("actor@example.com");
        UUID targetId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();

        savedEvent(actor, AuditEventType.JOB_CREATED, "Job", targetId);
        savedEvent(actor, AuditEventType.JOB_CANCELLED, "Job", targetId);
        savedEvent(actor, AuditEventType.JOB_CREATED, "Job", otherId);
        savedEvent(actor, AuditEventType.SCHEDULE_CREATED, "JobSchedule", targetId);

        List<AuditEvent> result = auditEventRepository.findByTargetTypeAndTargetId("Job", targetId);

        assertThat(result).hasSize(2)
                .allMatch(e -> "Job".equals(e.getTargetType()) && targetId.equals(e.getTargetId()));
    }
}
