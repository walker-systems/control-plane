package dev.jwalker.controlplane.api.schedules.web.dto;

import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record JobScheduleCreateRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull JobType type,
        @NotBlank String payloadJson,
        JobPriority priority,
        @PositiveOrZero Integer maxRetries,
        @NotBlank String cron,
        @NotBlank String timezone
) {
}
