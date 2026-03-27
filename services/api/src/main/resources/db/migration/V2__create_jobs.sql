CREATE TABLE jobs (
                      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                      owner_user_id UUID NOT NULL,
                      type VARCHAR(100) NOT NULL,
                      payload_json JSONB,
                      status VARCHAR(50) NOT NULL,
                      priority VARCHAR(50) NOT NULL,
                      idempotency_key VARCHAR(255),
                      max_retries INT NOT NULL DEFAULT 3,
                      created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

                      CONSTRAINT fk_jobs_owner
                          FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE RESTRICT,

                      CONSTRAINT chk_jobs_status
                          CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'DEAD_LETTER')),

                      CONSTRAINT chk_jobs_priority
                          CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),

                      CONSTRAINT chk_jobs_max_retries
                          CHECK (max_retries >= 0)
);

CREATE UNIQUE INDEX uq_jobs_idempotency_key
    ON jobs(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_jobs_owner_user_id
    ON jobs(owner_user_id);

CREATE INDEX idx_jobs_status
    ON jobs(status);

CREATE INDEX idx_jobs_type
    ON jobs(type);

CREATE INDEX idx_jobs_created_at
    ON jobs(created_at);
