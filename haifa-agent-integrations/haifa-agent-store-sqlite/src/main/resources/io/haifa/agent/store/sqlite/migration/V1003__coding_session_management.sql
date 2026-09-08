ALTER TABLE coding_session_activity
    ADD COLUMN session_status TEXT NOT NULL DEFAULT 'ACTIVE'
    CHECK (session_status IN ('ACTIVE', 'ARCHIVED', 'CLOSED', 'DELETED'));

CREATE INDEX idx_coding_session_scope_status_activity
    ON coding_session_activity(
        tenant_id, principal_id, principal_type, project_id, session_status,
        last_activity_at_ms DESC, session_id DESC
    );
