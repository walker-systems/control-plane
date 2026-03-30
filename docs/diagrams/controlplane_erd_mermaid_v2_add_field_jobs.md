# ControlPlane ERD

> Note: Mermaid ER diagrams use `PK`, `FK`, and `UK` as key markers.
> - `PK` = primary key
> - `FK` = foreign key
> - `UK` = unique key

```mermaid
erDiagram
    USERS {
        UUID id PK
        VARCHAR email UK
        VARCHAR password_hash
        VARCHAR status
        TIMESTAMP_TZ created_at
        TIMESTAMP_TZ last_login_at
    }

    ROLES {
        UUID id PK
        VARCHAR name UK
    }

    USER_ROLES {
        UUID user_id FK
        UUID role_id FK
    }

    REFRESH_TOKENS {
        UUID id PK
        UUID user_id FK
        VARCHAR token_hash UK
        TIMESTAMP_TZ expires_at
        TIMESTAMP_TZ revoked_at
        TIMESTAMP_TZ created_at
    }

    JOBS {
        UUID id PK
        UUID owner_user_id FK
        UUID source_schedule_id FK
        JSONB payload_json
        VARCHAR type
        VARCHAR status
        VARCHAR priority
        VARCHAR idempotency_key UK
        INT max_retries
        TIMESTAMP_TZ created_at
        TIMESTAMP_TZ updated_at
    }

    JOB_SCHEDULES {
        UUID id PK
        UUID owner_user_id FK
        JSONB payload_json
        VARCHAR type
        VARCHAR priority
        INT max_retries
        VARCHAR cron_expression
        VARCHAR timezone
        TIMESTAMP_TZ next_run_at
        TIMESTAMP_TZ last_enqueued_at
        BOOLEAN paused
        TIMESTAMP_TZ created_at
        TIMESTAMP_TZ updated_at
    }

    JOB_EXECUTIONS {
        UUID id PK
        UUID job_id FK
        VARCHAR worker_id
        INT attempt_number
        VARCHAR status
        TIMESTAMP_TZ started_at
        TIMESTAMP_TZ finished_at
        TIMESTAMP_TZ lease_expires_at
        TEXT error_message
        TEXT output_summary
        TIMESTAMP_TZ created_at
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
        TIMESTAMP_TZ created_at
    }

    USERS ||--o{ USER_ROLES : has
    ROLES ||--o{ USER_ROLES : assigned_in

    USERS ||--o{ REFRESH_TOKENS : owns
    USERS ||--o{ JOBS : owns
    USERS ||--o{ JOB_SCHEDULES : owns
    USERS o|--o{ AUDIT_EVENTS : performs

    JOB_SCHEDULES o|--o{ JOBS : generates
    JOBS ||--o{ JOB_EXECUTIONS : has
```

## Notes

- `USER_ROLES` is the join table for the many-to-many relationship between `USERS` and `ROLES`.
- `USER_ROLES` has a composite primary key of `(user_id, role_id)`.
- `REFRESH_TOKENS`, `JOBS`, and `JOB_SCHEDULES` all belong to a user.
- `JOBS.source_schedule_id` is nullable:
  - `NULL` means the job was created manually
  - a value means the job was generated from a recurring schedule
- `JOB_EXECUTIONS` stores per-attempt execution history for a job.
- `AUDIT_EVENTS.actor_user_id` is nullable, so an audit event may exist even if the actor is later removed or unavailable.
- `JOBS` and `JOB_SCHEDULES` are intentionally separate:
  - `JOBS` represent concrete units of work
  - `JOB_SCHEDULES` represent recurring templates that enqueue jobs over time
