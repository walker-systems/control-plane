-- V7 renamed paused -> enabled and flipped existing values, but Postgres
-- preserves the original DEFAULT FALSE through a RENAME COLUMN. Under the
-- new semantics (true = active) that means any insert that omits the column
-- would default to disabled, the opposite of intent. Set the default to
-- match the new meaning.
ALTER TABLE job_schedules
    ALTER COLUMN enabled SET DEFAULT TRUE;
