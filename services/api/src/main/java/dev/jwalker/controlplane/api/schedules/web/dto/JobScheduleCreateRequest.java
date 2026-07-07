package dev.jwalker.controlplane.api.schedules.web.dto;

import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record JobScheduleCreateRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull JobType type,
        @NotBlank String payloadJson,
        JobPriority priority,
        // Matches JobCreateRequest's @Max(20) cap. ScheduleMaterializer
        // copies this into every materialized job, so an uncapped value
        // here would let a schedule with maxRetries=100 route around the
        // direct-create cap and produce jobs whose execution history
        // exceeds the /executions endpoint's 100-row limit.
        @PositiveOrZero @Max(20) Integer maxRetries,
        @NotBlank String cron,
        @NotBlank String timezone
) {
}
