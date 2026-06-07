package dev.jwalker.controlplane.api.schedules.web.dto;

import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record JobScheduleUpdateRequest(
        @Size(max = 120) String name,
        String payloadJson,
        JobPriority priority,
        @PositiveOrZero Integer maxRetries,
        String cron,
        String timezone
) {
}
