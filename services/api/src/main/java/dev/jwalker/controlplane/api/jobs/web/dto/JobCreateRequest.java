package dev.jwalker.controlplane.api.jobs.web.dto;

import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record JobCreateRequest(
        @NotNull JobType type,
        @NotBlank String payloadJson,
        JobPriority priority,
        // Cap keeps execution-history responses bounded: maxRetries=20
        // means up to 21 rows per lifecycle, well under the executions
        // endpoint's 100-row response cap. Prevents an authenticated
        // user from creating a job with maxRetries=100_000 and forcing
        // the endpoint to materialize thousands of executions.
        @PositiveOrZero @Max(20) Integer maxRetries,
        @Size(max = 255) String idempotencyKey
) {
}
