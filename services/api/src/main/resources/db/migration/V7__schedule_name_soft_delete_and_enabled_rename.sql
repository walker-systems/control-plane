ALTER TABLE job_schedules
    RENAME COLUMN paused TO enabled;

UPDATE job_schedules
    SET enabled = NOT enabled;

ALTER INDEX idx_job_schedules_paused
    RENAME TO idx_job_schedules_enabled;


ALTER TABLE job_schedules
    ADD COLUMN name VARCHAR(120);

UPDATE job_schedules
    SET name = 'schedule-' || id::text
    WHERE name IS NULL;

ALTER TABLE job_schedules
    ALTER COLUMN name SET NOT NULL;


ALTER TABLE job_schedules
    ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;

CREATE UNIQUE INDEX uq_job_schedules_owner_name
    ON job_schedules(owner_user_id, name)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_job_schedules_deleted_at
    ON job_schedules(deleted_at);
