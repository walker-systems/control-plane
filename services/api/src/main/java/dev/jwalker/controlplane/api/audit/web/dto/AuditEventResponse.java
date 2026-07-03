package dev.jwalker.controlplane.api.audit.web.dto;

import dev.jwalker.controlplane.api.audit.model.AuditEvent;
import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,
        UUID actorUserId,
        String actorEmail,
        AuditEventType eventType,
        String targetType,
        UUID targetId,
        String metadataJson,
        String ipAddress,
        String userAgent,
        OffsetDateTime createdAt
) {
    public static AuditEventResponse from(AuditEvent e) {
        UUID actorId = e.hasActor() ? e.getActor().getId() : null;
        String actorEmail = e.hasActor() ? e.getActor().getEmail() : null;
        return new AuditEventResponse(
                e.getId(),
                actorId,
                actorEmail,
                e.getEventType(),
                e.getTargetType(),
                e.getTargetId(),
                e.getMetadataJson(),
                e.getIpAddress(),
                e.getUserAgent(),
                e.getCreatedAt());
    }
}
