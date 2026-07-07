package dev.jwalker.controlplane.api.jobs.web.dto;

import dev.jwalker.controlplane.api.jobs.model.JobExecution;
import dev.jwalker.controlplane.api.jobs.model.JobExecutionStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record JobExecutionResponse(
        UUID id,
        UUID jobId,
        int attemptNumber,
        JobExecutionStatus status,
        String workerId,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime leaseExpiresAt,
        String errorMessage,
        String outputSummary,
        OffsetDateTime createdAt
) {
    public static JobExecutionResponse from(JobExecution exec) {
        return new JobExecutionResponse(
                exec.getId(),
                exec.getJob().getId(),
                exec.getAttemptNumber(),
                exec.getStatus(),
                exec.getWorkerId(),
                exec.getStartedAt(),
                exec.getFinishedAt(),
                exec.getLeaseExpiresAt(),
                exec.getErrorMessage(),
                exec.getOutputSummary(),
                exec.getCreatedAt());
    }
}
