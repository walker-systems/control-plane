CREATE TABLE job_executions (
                                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                job_id UUID NOT NULL,
                                worker_id VARCHAR(255),
                                attempt_number INT NOT NULL,

                                status VARCHAR(50) NOT NULL,
                                started_at TIMESTAMP WITH TIME ZONE,
                                finished_at TIMESTAMP WITH TIME ZONE,
                                lease_expires_at TIMESTAMP WITH TIME ZONE,

                                error_message TEXT,
                                output_summary TEXT,

                                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                CONSTRAINT fk_job_executions_job
                                    FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE,

                                CONSTRAINT chk_job_executions_attempt_number
                                    CHECK (attempt_number >= 1),

                                CONSTRAINT chk_job_executions_status
                                    CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'))
);

CREATE UNIQUE INDEX uq_job_executions_job_attempt
    ON job_executions(job_id, attempt_number);

CREATE INDEX idx_job_executions_job_id
    ON job_executions(job_id);

CREATE INDEX idx_job_executions_worker_id
    ON job_executions(worker_id);

CREATE INDEX idx_job_executions_status
    ON job_executions(status);

CREATE INDEX idx_job_executions_started_at
    ON job_executions(started_at);
