# API

All endpoints are JSON over `/api/*`, authenticated with a JWT bearer
token unless noted. Errors follow RFC 9457 (`application/problem+json`)
with a machine-readable `reason` property on domain errors.

## Auth

| Method | Path | Notes |
|---|---|---|
| POST | `/api/auth/login` | email + password → access token (15 min) + refresh token (7 days). Unauthenticated. |
| POST | `/api/auth/refresh` | refresh token → new token pair. Unauthenticated. |
| POST | `/api/auth/logout` | Revokes the refresh token server-side. Unauthenticated. |

The access token's `roles` claim (`USER`, `OPERATOR`, `ADMIN`) drives
authorization; the UI decodes the same claim for display gating.

## Jobs

| Method | Path | Notes |
|---|---|---|
| POST | `/api/jobs` | Create. `type`, `payloadJson` required; `priority`, `maxRetries` (≤ 20), `idempotencyKey` optional. |
| GET | `/api/jobs` | Paged list. Filters: `status`, `type`, `priority`, `ownerId`, `sourceScheduleId`. Owners see their own; OPERATOR/ADMIN see all. |
| GET | `/api/jobs/stats` | Counts by status (all statuses present, zero-padded). |
| GET | `/api/jobs/{id}` | Single job. |
| GET | `/api/jobs/{id}/executions` | Attempt history (worker, lease, timing, outcome). |
| POST | `/api/jobs/{id}/cancel` | PENDING → immediate; RUNNING → deferred until the attempt completes. 409 `reason` on invalid state. |
| POST | `/api/jobs/{id}/retry` | DEAD_LETTER → PENDING with a fresh attempt budget. 409 on invalid state. |

## Schedules

| Method | Path | Notes |
|---|---|---|
| POST | `/api/schedules` | 6-field Spring cron + IANA timezone. 400 `INVALID_CRON` / `INVALID_TIMEZONE`, 409 `DUPLICATE_NAME`. |
| GET | `/api/schedules` | Paged list. Filters: `enabled`, `type`, `priority`, `ownerId`. |
| GET | `/api/schedules/{id}` | Single schedule. |
| PATCH | `/api/schedules/{id}` | Partial update — null fields untouched. |
| POST | `/api/schedules/{id}/pause` | Disables; clears `nextRunAt`. |
| POST | `/api/schedules/{id}/resume` | Re-enables; recomputes `nextRunAt`. |
| DELETE | `/api/schedules/{id}` | Soft delete. Materialized jobs are unaffected. |

## Audit & users

| Method | Path | Notes |
|---|---|---|
| GET | `/api/audit/target/{type}/{id}` | Audit trail for one entity. OPERATOR/ADMIN only. |
| GET | `/api/users/me` | Caller's profile. |

## User management (ADMIN only)

There is deliberately no self-service registration — accounts exist
because an admin created one (or bootstrap seeded it). The gate lives
in the service layer; non-admins get 403 on every endpoint below.

| Method | Path | Notes |
|---|---|---|
| GET | `/api/users` | Paged list with roles and status. |
| POST | `/api/users` | Create. BCrypt-hashed password (min 12 chars), roles validated against the roles table (default `USER`). 409 `DUPLICATE_EMAIL` (race-safe via constraint translation), 400 `UNKNOWN_ROLE`. |
| PATCH | `/api/users/{id}` | Partial update of `status` and/or `roles` (null = untouched). Locking/disabling revokes all refresh tokens. 409 `SELF_MODIFICATION` on your own account. |

Status semantics: `LOCKED`/`DISABLED` accounts are refused at login and
refresh with 403 `ACCOUNT_LOCKED`/`ACCOUNT_DISABLED` — distinct from
401 invalid-credentials. Every mutation is audited
(`USER_CREATED`, `USER_ROLE_CHANGED`, `USER_STATUS_CHANGED`).

## Operational endpoints

Spring Actuator (`/actuator/health`, `/metrics`, `/prometheus`) is
served by the API container but **not** proxied by Caddy — it is
reachable only inside the compose network.
