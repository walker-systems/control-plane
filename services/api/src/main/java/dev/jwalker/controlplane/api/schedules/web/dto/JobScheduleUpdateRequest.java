package dev.jwalker.controlplane.api.schedules.web.dto;

import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// PATCH-style contract: every field is nullable. The service treats null as
// "don't touch this field," so clients send only what they want to change.
// Bean Validation runs only on present values — @Size, @PositiveOrZero, and
// @Max all pass through null, so omitted fields don't trigger 400s.
public record JobScheduleUpdateRequest(
        @Size(max = 120) String name,
        String payloadJson,
        JobPriority priority,
        // Same cap as JobScheduleCreateRequest and JobCreateRequest —
        // a raised value on an existing schedule would leak past the
        // executions endpoint's response cap through materialized jobs.
        @PositiveOrZero @Max(20) Integer maxRetries,
        String cron,
        String timezone
) {
}
