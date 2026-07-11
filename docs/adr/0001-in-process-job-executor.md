# ADR 0001: In-process job executor

**Status**: Accepted
**Date**: 2026-07-11

## Context

The original design (May 2026 roadmap) called for a separate `services/worker/` Spring Boot application that would consume job messages from RabbitMQ and run handlers out-of-process from the API. A stub existed but was never wired up.

By the time Phase 7 (retry, lease, dead-letter) landed, the API service already contained a working executor loop: `JobExecutor` picks PENDING jobs on a `@Scheduled` tick, runs handlers via `JobHandlerRegistry`, and writes execution rows. Watchdog reclaim and RUNNING-cancel (PRs #15, #16) built on this in-process design without ever touching the worker stub.

Continuing to carry the stub costs review overhead, misleads new readers about where execution happens, and creates pressure to wire up RabbitMQ + a shared entity library on a schedule that isn't driven by real load.

## Decision

Adopt the in-process executor as the intended architecture. Delete `services/worker/` and remove it from the root `pom.xml` `<modules>`. Job execution runs inside `services/api/` on the same JVM as HTTP endpoints. Handlers are `@Component` classes discovered by classpath scan and indexed by `JobHandlerRegistry`.

## Consequences

**Positives**
- One JVM, one deploy, one set of migrations. Simpler dev-loop, simpler prod stack.
- No broker in the critical path. Postgres is the queue (via `FOR UPDATE SKIP LOCKED` in `JobRepository.findPendingForUpdate`).
- No cross-service serialization contract for jobs — the `Job` entity is the type.
- Cancel and watchdog can run in the same process as execution, keeping their transaction scopes tight.

**Negatives**
- Horizontal worker scaling is coupled to horizontal API scaling. Adding executor capacity means adding API replicas.
- Long-running handlers hold JVM resources on the same process serving user requests. Guarded today by:
    - Per-job lease renewal (`JobExecutorTxOps.startAttempt`) capping any single attempt at 5 minutes before the watchdog reclaims it.
    - Handler timeouts / bulkhead patterns are still an open item if handler count grows.

**Extraction path if load ever demands it**
The `JobHandler` interface is the seam. Extracting means:
1. Splitting `JobHandlerRegistry` and its handlers into a new `services/worker/` module.
2. Introducing a message contract (RabbitMQ topic or Postgres LISTEN/NOTIFY) that carries `(jobId, execId, attemptNumber)` — enough for the worker to load and complete without depending on the picker's tx.
3. Moving the `complete*` methods on `JobExecutorTxOps` to the worker so it owns the finalization transaction.

The Postgres-as-queue pattern with `FOR UPDATE SKIP LOCKED` already gives multiple worker processes safe concurrent pickup with no broker at all. If that scales far enough, RabbitMQ may never be needed.

## Related

- PR #15 (watchdog + RUNNING-cancel) — assumes in-process execution when reasoning about lock ordering.
- PR #16 (per-job lease renewal) — mitigates one of the "long-running handler" downsides above.
