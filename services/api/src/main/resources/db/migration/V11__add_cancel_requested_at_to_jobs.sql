-- cancel_requested_at is the "please cancel" flag for RUNNING jobs.
-- Cancel API sets it; the executor's complete phase and the watchdog
-- check it and, if non-null, transition the Job to CANCELLED instead
-- of the natural outcome. NULL means "not requested."
--
-- Nullable — existing rows keep NULL (never requested). No index yet:
-- lookups are always by primary key (the executor/watchdog already
-- located the row via other criteria); there's no query pattern that
-- scans by cancel_requested_at.
ALTER TABLE jobs
    ADD COLUMN cancel_requested_at TIMESTAMP WITH TIME ZONE;
