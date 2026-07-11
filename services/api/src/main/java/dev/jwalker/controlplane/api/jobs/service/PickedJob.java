package dev.jwalker.controlplane.api.jobs.service;

import dev.jwalker.controlplane.api.jobs.model.Job;
import java.util.UUID;

// One entry in the pick phase's output — the Job the pick transitioned to
// RUNNING, and the id of the JobExecution row that captures this attempt.
// The Job here is a detached JPA entity: the pick tx has already committed,
// so eagerly-loaded fields (id, type, payloadJson, status, priority,
// maxRetries, etc.) are safe to read, but lazy associations like owner
// would throw LazyInitializationException. Handlers that need lazy fields
// should have them pre-loaded by the pick phase — or refactor them into
// this record. The complete phase re-fetches by id (with its own lock)
// rather than trusting the detached entity's state.
record PickedJob(Job job, UUID execId, int attemptNumber) {
}
