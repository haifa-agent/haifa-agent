CREATE TABLE coding_session_activity (
    session_id TEXT PRIMARY KEY,
    schema_version TEXT NOT NULL,
    project_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    principal_id TEXT NOT NULL,
    principal_type TEXT NOT NULL,
    display_name TEXT NOT NULL,
    active_run_id TEXT,
    active_run_version INTEGER,
    active_dispatch_key TEXT,
    created_at_ms INTEGER NOT NULL,
    last_activity_at_ms INTEGER NOT NULL,
    revision INTEGER NOT NULL,
    CHECK ((active_run_id IS NULL) = (active_run_version IS NULL)),
    FOREIGN KEY (session_id) REFERENCES project_product_session(session_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_coding_session_active_dispatch
    ON coding_session_activity(active_dispatch_key)
    WHERE active_dispatch_key IS NOT NULL;

CREATE INDEX idx_coding_session_scope_activity
    ON coding_session_activity(
        tenant_id, principal_id, principal_type, project_id, last_activity_at_ms DESC, session_id DESC
    );

CREATE TABLE coding_session_command (
    caller_scope_digest TEXT NOT NULL,
    operation TEXT NOT NULL,
    idempotency_key_digest TEXT NOT NULL,
    schema_version TEXT NOT NULL,
    request_digest TEXT NOT NULL,
    dispatch_key TEXT NOT NULL UNIQUE,
    session_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    content_nonce BLOB NOT NULL,
    content_ciphertext BLOB NOT NULL,
    content_digest TEXT NOT NULL,
    run_id TEXT,
    created_at_ms INTEGER NOT NULL,
    PRIMARY KEY (caller_scope_digest, operation, idempotency_key_digest)
);

CREATE TABLE coding_follow_up (
    follow_up_id TEXT PRIMARY KEY,
    schema_version TEXT NOT NULL,
    session_id TEXT NOT NULL,
    bound_run_id TEXT NOT NULL,
    content_nonce BLOB NOT NULL,
    content_ciphertext BLOB NOT NULL,
    content_digest TEXT NOT NULL,
    idempotency_key_digest TEXT NOT NULL,
    request_digest TEXT NOT NULL,
    dispatch_key TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL,
    sequence_no INTEGER NOT NULL,
    dispatched_run_id TEXT,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    claimed_at_ms INTEGER,
    restored_at_ms INTEGER,
    revision INTEGER NOT NULL,
    UNIQUE (session_id, idempotency_key_digest),
    UNIQUE (session_id, sequence_no),
    CHECK (status IN ('PENDING', 'CLAIMED', 'DISPATCHED', 'RESTORED')),
    CHECK ((status = 'DISPATCHED') = (dispatched_run_id IS NOT NULL)),
    FOREIGN KEY (session_id) REFERENCES coding_session_activity(session_id) ON DELETE CASCADE
);

CREATE INDEX idx_coding_follow_up_dispatch
    ON coding_follow_up(session_id, status, sequence_no);
