package dev.jwalker.controlplane.api.jobs.model;

public enum JobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    DEAD_LETTER
}
