package dev.jwalker.controlplane.api.jobs.web.dto;

import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record JobCreateRequest(
        @NotNull JobType type,
        @NotBlank String payloadJson,
        JobPriority priority,
        @PositiveOrZero Integer maxRetries,
        @Size(max = 255) String idempotencyKey
) {
}
