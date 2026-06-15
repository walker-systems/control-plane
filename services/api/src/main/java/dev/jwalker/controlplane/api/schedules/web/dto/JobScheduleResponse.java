package dev.jwalker.controlplane.api.schedules.web.dto;

import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.schedules.model.JobSchedule;
import java.time.OffsetDateTime;
import java.util.UUID;

public record JobScheduleResponse(
        UUID id,
        UUID ownerId,
        String ownerEmail,
        String name,
        JobType type,
        String payloadJson,
        JobPriority priority,
        int maxRetries,
        String cron,
        String timezone,
        boolean enabled,
        OffsetDateTime nextRunAt,
        OffsetDateTime lastEnqueuedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static JobScheduleResponse from(JobSchedule s) {
        return new JobScheduleResponse(
                s.getId(),
                s.getOwner().getId(),
                s.getOwner().getEmail(),
                s.getName(),
                s.getType(),
                s.getPayloadJson(),
                s.getPriority(),
                s.getMaxRetries(),
                s.getCronExpression(),
                s.getTimezone(),
                s.isEnabled(),
                s.getNextRunAt(),
                s.getLastEnqueuedAt(),
                s.getCreatedAt(),
                s.getUpdatedAt());
    }
}
