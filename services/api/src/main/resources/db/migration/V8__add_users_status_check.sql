ALTER TABLE users
    ADD CONSTRAINT chk_users_status
        CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'));
