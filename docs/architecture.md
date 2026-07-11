# Architecture

Control Plane is a single Spring Boot service (`services/api/`) plus a Postgres database. There is no separate worker process, no broker.

## Services

- **`services/api/`** — Spring Boot 4 / Java 25. HTTP endpoints, JWT auth, JPA persistence, and the in-process job executor all live here. The executor picks PENDING jobs on a scheduled tick, runs handlers, and writes execution rows.
- **`services/ui/`** — Vite + React 19 + TypeScript + Tailwind. Public-facing demo UI. Dev server proxies `/api/*` to the API on :8080; prod deploys behind a reverse proxy that owns the same routing.

## Data

Postgres is the source of truth for identity, jobs, schedules, executions, and audit events. It also serves as the job queue via `FOR UPDATE SKIP LOCKED`. Migrations live in `services/api/src/main/resources/db/migration/`.

## Key architectural decisions

- [ADR 0001: In-process job executor](adr/0001-in-process-job-executor.md) — no separate worker service; execution shares the API JVM.
