-- available_at gates when the executor picks the job up. Newly-created and
-- materialized jobs default to NOW() so they're immediately eligible. On
-- retry, the executor sets available_at = now + backoff so failed attempts
-- delay rather than storm the executor.
ALTER TABLE jobs
    ADD COLUMN available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW();

-- Composite index tuned for the executor query: WHERE status = 'PENDING' AND
-- available_at <= now(). Postgres can range-scan available_at within the
-- status='PENDING' subset without scanning terminal-state rows.
CREATE INDEX idx_jobs_status_available_at
    ON jobs(status, available_at);
