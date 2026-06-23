package dev.jwalker.controlplane.api.audit.repository;

import dev.jwalker.controlplane.api.audit.model.AuditEvent;
import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.users.model.User;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByActor(User actor);

    List<AuditEvent> findByEventType(AuditEventType eventType);

    List<AuditEvent> findByCreatedAtAfter(OffsetDateTime cutoff);

    List<AuditEvent> findByTargetTypeAndTargetId(String targetType, UUID targetId);

    // Time-range filtering (since/until) is deferred — Postgres can't
    // infer the parameter type for ":since IS NULL" with a timestamp
    // parameter; the four filters here cover the main use cases. Add
    // since/until later via Spring Data's Specification API which
    // builds the query dynamically.
    @Query(
            value = """
                    SELECT a FROM AuditEvent a
                    LEFT JOIN FETCH a.actor
                    WHERE (:eventType IS NULL OR a.eventType = :eventType)
                      AND (:actorUserId IS NULL OR a.actor.id = :actorUserId)
                      AND (:targetType IS NULL OR a.targetType = :targetType)
                      AND (:targetId IS NULL OR a.targetId = :targetId)
                    """,
            countQuery = """
                    SELECT COUNT(a) FROM AuditEvent a
                    WHERE (:eventType IS NULL OR a.eventType = :eventType)
                      AND (:actorUserId IS NULL OR a.actor.id = :actorUserId)
                      AND (:targetType IS NULL OR a.targetType = :targetType)
                      AND (:targetId IS NULL OR a.targetId = :targetId)
                    """)
    Page<AuditEvent> search(
            @Param("eventType") AuditEventType eventType,
            @Param("actorUserId") UUID actorUserId,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId,
            Pageable pageable);
}
