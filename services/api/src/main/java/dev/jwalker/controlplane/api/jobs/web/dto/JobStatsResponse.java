package dev.jwalker.controlplane.api.jobs.web.dto;

import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import java.util.EnumMap;
import java.util.Map;

// Counts by status. Every JobStatus value is always present in the
// map — statuses with zero jobs are populated as 0 — so the UI can
// render tiles without null-checking each key.
public record JobStatsResponse(Map<JobStatus, Long> counts) {

    public static JobStatsResponse from(Map<JobStatus, Long> raw) {
        // EnumMap preserves the enum's declaration order in iteration,
        // which is the natural display order (PENDING → RUNNING →
        // terminal states).
        Map<JobStatus, Long> full = new EnumMap<>(JobStatus.class);
        for (JobStatus status : JobStatus.values()) {
            full.put(status, raw.getOrDefault(status, 0L));
        }
        return new JobStatsResponse(full);
    }
}
