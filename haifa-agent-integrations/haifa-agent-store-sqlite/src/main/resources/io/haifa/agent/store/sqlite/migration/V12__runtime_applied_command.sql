CREATE TABLE IF NOT EXISTS runtime_applied_command (
    caller_scope    TEXT NOT NULL,
    operation       TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_digest  TEXT,
    result_version  INTEGER NOT NULL,
    result_payload  TEXT NOT NULL,
    applied_at      INTEGER NOT NULL CHECK (applied_at >= 0),
    PRIMARY KEY (caller_scope, operation, idempotency_key)
);
