package dev.jwalker.controlplane.api.jobs.service;

import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobType;

// One implementation per JobType. handle() returns a short output summary
// on success (may be null) or throws any Exception on failure — the
// executor treats non-null return as SUCCEEDED and any throw as FAILED.
public interface JobHandler {

    JobType getSupportedType();

    String handle(Job job) throws Exception;
}
