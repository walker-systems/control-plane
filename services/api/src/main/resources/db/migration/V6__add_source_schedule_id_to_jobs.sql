ALTER TABLE jobs
    ADD COLUMN source_schedule_id UUID;

ALTER TABLE jobs
    ADD CONSTRAINT fk_jobs_source_schedule
        FOREIGN KEY (source_schedule_id)
            REFERENCES job_schedules(id)
            ON DELETE SET NULL;

CREATE INDEX idx_jobs_source_schedule_id
    ON jobs(source_schedule_id);
