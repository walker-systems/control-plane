CREATE TABLE job_schedules (
                               id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                               owner_user_id UUID NOT NULL,
                               type VARCHAR(100) NOT NULL,
                               payload_json JSONB,
                               priority VARCHAR(50) NOT NULL,
                               max_retries INT NOT NULL DEFAULT 3,

                               cron_expression VARCHAR(120) NOT NULL,
                               timezone VARCHAR(100) NOT NULL DEFAULT 'UTC',
                               next_run_at TIMESTAMP WITH TIME ZONE,
                               last_enqueued_at TIMESTAMP WITH TIME ZONE,

                               paused BOOLEAN NOT NULL DEFAULT FALSE,

                               created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

                               CONSTRAINT fk_job_schedules_owner
                                   FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE RESTRICT,

                               CONSTRAINT chk_job_schedules_priority
                                   CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),

                               CONSTRAINT chk_job_schedules_max_retries
                                   CHECK (max_retries >= 0)
);

CREATE INDEX idx_job_schedules_owner_user_id
    ON job_schedules(owner_user_id);

CREATE INDEX idx_job_schedules_next_run_at
    ON job_schedules(next_run_at);

CREATE INDEX idx_job_schedules_paused
    ON job_schedules(paused);

CREATE INDEX idx_job_schedules_type
    ON job_schedules(type);
