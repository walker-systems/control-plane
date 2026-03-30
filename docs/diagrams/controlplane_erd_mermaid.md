# ControlPlane ERD

```mermaid
erDiagram
    USERS {
        UUID id PK
        VARCHAR email UK
        VARCHAR password_hash
        VARCHAR status
        TIMESTAMPTZ created_at
        TIMESTAMPTZ last_login_at
    }

    ROLES {
        UUID id PK
        VARCHAR name UK
    }

    USER_ROLES {
        UUID user_id PK, FK
        UUID role_id PK, FK
    }

    REFRESH_TOKENS {
        UUID id PK
        UUID user_id FK
        VARCHAR token_hash UK
        TIMESTAMPTZ expires_at
        TIMESTAMPTZ revoked_at
        TIMESTAMPTZ created_at
    }

    JOBS {
        UUID id PK
        UUID owner_user_id FK
        VARCHAR type
        JSONB payload_json
        VARCHAR status
        VARCHAR priority
        VARCHAR idempotency_key UK
        INT max_retries
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    JOB_SCHEDULES {
        UUID id PK
        UUID owner_user_id FK
        VARCHAR type
        JSONB payload_json
        VARCHAR priority
        INT max_retries
        VARCHAR cron_expression
        VARCHAR timezone
        TIMESTAMPTZ next_run_at
        TIMESTAMPTZ last_enqueued_at
        BOOLEAN paused
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    JOB_EXECUTIONS {
        UUID id PK
        UUID job_id FK
        VARCHAR worker_id
        INT attempt_number
        VARCHAR status
        TIMESTAMPTZ started_at
        TIMESTAMPTZ finished_at
        TIMESTAMPTZ lease_expires_at
        TEXT error_message
        TEXT output_summary
        TIMESTAMPTZ created_at
    }

    AUDIT_EVENTS {
        UUID id PK
        UUID actor_user_id FK
        VARCHAR event_type
        VARCHAR target_type
        UUID target_id
        JSONB metadata_json
        VARCHAR ip_address
        TEXT user_agent
        TIMESTAMPTZ created_at
    }

    USERS ||--o{ USER_ROLES : has
    ROLES ||--o{ USER_ROLES : assigned_in

    USERS ||--o{ REFRESH_TOKENS : owns
    USERS ||--o{ JOBS : owns
    USERS ||--o{ JOB_SCHEDULES : owns
    USERS o|--o{ AUDIT_EVENTS : performs

    JOBS ||--o{ JOB_EXECUTIONS : has
```

## Notes

- `USER_ROLES` is the join table for the many-to-many relationship between `USERS` and `ROLES`.
- `REFRESH_TOKENS`, `JOBS`, and `JOB_SCHEDULES` all belong to a user.
- `JOB_EXECUTIONS` stores per-attempt execution history for a job.
- `AUDIT_EVENTS.actor_user_id` is nullable, so an audit event may exist even if the actor is later removed or unavailable.
- `JOBS` and `JOB_SCHEDULES` are intentionally separate:
  - `JOBS` represent concrete units of work
  - `JOB_SCHEDULES` represent recurring templates that enqueue jobs over time
