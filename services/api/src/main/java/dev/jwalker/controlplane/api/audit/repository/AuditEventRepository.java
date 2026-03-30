package dev.jwalker.controlplane.api.audit.repository;

import dev.jwalker.controlplane.api.audit.model.AuditEvent;
import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.users.model.User;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByActor(User actor);

    List<AuditEvent> findByEventType(AuditEventType eventType);

    List<AuditEvent> findByCreatedAtAfter(OffsetDateTime cutoff);

    List<AuditEvent> findByTargetTypeAndTargetId(String targetType, UUID targetId);
}
