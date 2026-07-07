package dev.jwalker.controlplane.api.jobs.web.dto;

import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import java.time.OffsetDateTime;
import java.util.UUID;

public record JobResponse(
        UUID id,
        UUID ownerId,
        String ownerEmail,
        JobType type,
        String payloadJson,
        JobStatus status,
        JobPriority priority,
        String idempotencyKey,
        int maxRetries,
        long attemptCount,
        OffsetDateTime availableAt,
        UUID sourceScheduleId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    // attemptCount is derived, not stored on Job — callers count JobExecution
    // rows and pass in the total. Zero for a freshly created job that hasn't
    // been picked up yet.
    public static JobResponse from(Job job, long attemptCount) {
        return new JobResponse(
                job.getId(),
                job.getOwner().getId(),
                job.getOwner().getEmail(),
                job.getType(),
                job.getPayloadJson(),
                job.getStatus(),
                job.getPriority(),
                job.getIdempotencyKey(),
                job.getMaxRetries(),
                attemptCount,
                job.getAvailableAt(),
                job.getSourceScheduleId(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}
