# Roadmap

## Built

- **Job queue core** — Postgres-backed queue with `FOR UPDATE SKIP
  LOCKED` claims, priorities, retry with backoff, dead-lettering,
  idempotency keys.
- **Executions & leases** — per-attempt records with lease expiry; a
  watchdog reclaims work lost to crashes and returns it to the queue.
- **Cancel semantics** — immediate for PENDING, deferred-to-attempt-end
  for RUNNING.
- **Schedules** — 6-field cron + timezone, pause/resume, soft delete;
  a scheduler tick materializes due schedules into jobs.
- **Auth & audit** — JWT with role claims, refresh-token revocation,
  append-only audit trail (OPERATOR/ADMIN read).
- **Operations UI** — React SPA: live dashboard, job list/detail with
  cancel/retry, schedule management with a create form, role-gated
  audit views.
- **Deployment** — Dockerized services, single-droplet compose stack
  behind Caddy (automatic HTTPS), GitHub Actions CD to
  [control-plane.dev](https://control-plane.dev) on every merge.
- **Local demo** — zero-config compose + seed script
  (`deploy/compose.demo.yml`, `scripts/seed-demo.sh`).

## Next

- **Standalone worker** — split the executor out of the API JVM into a
  separately scalable deployable. The claim path was written for this:
  no schema changes required (see
  [ADR 0001](adr/0001-in-process-job-executor.md)).
- **Job timeouts per type** — per-handler execution budgets distinct
  from lease duration.
- **Metrics dashboard** — surface the Prometheus endpoint via Grafana;
  queue depth, claim latency, handler duration percentiles.
- **Webhooks** — notify external systems on terminal job states.
- **Multi-tenancy hardening** — per-tenant rate limits and quotas.
