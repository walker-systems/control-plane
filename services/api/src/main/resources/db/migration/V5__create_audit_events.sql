CREATE TABLE audit_events (
                              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                              actor_user_id UUID,
                              event_type VARCHAR(100) NOT NULL,
                              target_type VARCHAR(100),
                              target_id UUID,
                              metadata_json JSONB,
                              ip_address VARCHAR(64),
                              user_agent TEXT,
                              created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

                              CONSTRAINT fk_audit_events_actor
                                  FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_audit_events_actor_user_id
    ON audit_events(actor_user_id);

CREATE INDEX idx_audit_events_event_type
    ON audit_events(event_type);

CREATE INDEX idx_audit_events_target_type
    ON audit_events(target_type);

CREATE INDEX idx_audit_events_target_id
    ON audit_events(target_id);

CREATE INDEX idx_audit_events_created_at
    ON audit_events(created_at);
