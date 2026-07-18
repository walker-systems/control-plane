# Control Plane

A distributed job orchestration platform: Postgres-backed job queue with
priorities, retries, cron schedules, lease-based crash recovery, and a
live operations UI.

**Live demo: [control-plane.dev](https://control-plane.dev)**

Built with Spring Boot 4 / Java 25 (virtual threads) and React 19 /
TypeScript. Jobs are claimed with `FOR UPDATE SKIP LOCKED` so any number
of workers can pull from the same queue without stepping on each other;
a watchdog reclaims work whose lease expired mid-flight.

## Run it locally

One command — pulls published images, no JDK or Node required:

```bash
docker compose -f deploy/compose.demo.yml up -d
```

Open **http://localhost:8000** and log in as `demo@control-plane.dev` /
`demo-password`. Then give the dashboard something to show:

```bash
./scripts/seed-demo.sh
```

That creates two cron schedules (one healthy, one deliberately flaky so
dead-letter and retry flows have something to act on) and a burst of
one-off jobs. Watch the dashboard tiles move — jobs run with simulated
handlers that take 6–16s, occasionally fail, and rarely dead-letter.

Tear down with `docker compose -f deploy/compose.demo.yml down -v`.

## What to look at

| | |
|---|---|
| Job lifecycle | PENDING → RUNNING → SUCCEEDED / FAILED (retry with backoff) → DEAD_LETTER |
| Cancel semantics | PENDING cancels instantly; RUNNING defers until the in-flight attempt completes |
| Schedules | 6-field cron + timezone, pause/resume, jobs link back to their schedule |
| Concurrency | `SKIP LOCKED` claims, exec-then-job lock ordering, lease watchdog |
| Auditing | Every state transition recorded; audit trail visible to OPERATOR/ADMIN |

## Repository layout

```
services/api   Spring Boot API + job executor (see docs/architecture.md)
services/ui    React SPA (Vite, TanStack Query, Tailwind)
deploy/        Production compose stack, Caddyfile, local demo compose
docs/          Architecture, ADRs, deployment runbook
```

## Docs

- [Architecture](docs/architecture.md)
- [Deployment](docs/deployment.md) — droplet + Caddy + GitHub Actions CD
- [ADR-0001: in-process job executor](docs/adr/0001-in-process-job-executor.md)
