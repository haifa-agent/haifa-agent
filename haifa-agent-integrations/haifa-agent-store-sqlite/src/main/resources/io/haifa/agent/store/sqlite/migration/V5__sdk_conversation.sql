-- Product-neutral Conversation Session index and command idempotency bindings.
-- Message bodies remain authoritative in the Runtime session_message table.

CREATE TABLE sdk_conversation (
    session_id TEXT PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    principal_id TEXT NOT NULL,
    principal_type TEXT NOT NULL,
    display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 256),
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    active_run_id TEXT,
    active_run_version INTEGER,
    active_dispatch_key TEXT,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    last_activity_at INTEGER NOT NULL CHECK (last_activity_at >= created_at),
    revision INTEGER NOT NULL CHECK (revision >= 0),
    FOREIGN KEY (session_id) REFERENCES session(session_id),
    CHECK ((active_run_id IS NULL) = (active_run_version IS NULL)),
    CHECK (active_run_id IS NULL OR active_dispatch_key IS NULL)
) STRICT;

CREATE INDEX idx_sdk_conversation_scope_activity
ON sdk_conversation(
    tenant_id,
    principal_id,
    principal_type,
    status,
    last_activity_at DESC,
    session_id DESC
);

CREATE TABLE sdk_conversation_command (
    dispatch_key TEXT PRIMARY KEY,
    caller_scope_digest TEXT NOT NULL,
    operation TEXT NOT NULL,
    idempotency_key_digest TEXT NOT NULL,
    request_digest TEXT NOT NULL,
    session_id TEXT NOT NULL,
    run_id TEXT,
    completed INTEGER NOT NULL CHECK (completed IN (0, 1)),
    result_revision INTEGER,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    CHECK ((completed = 1) = (result_revision IS NOT NULL)),
    UNIQUE (caller_scope_digest, operation, idempotency_key_digest)
) STRICT;

CREATE INDEX idx_sdk_conversation_command_session
ON sdk_conversation_command(session_id, created_at);
