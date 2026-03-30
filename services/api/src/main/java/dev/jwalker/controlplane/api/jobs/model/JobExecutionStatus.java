package dev.jwalker.controlplane.api.jobs.model;

public enum JobExecutionStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
