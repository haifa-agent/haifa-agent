-- SDK conversation metadata only: drop the command ledger and the duplicated Runtime state machine.

DROP TABLE IF EXISTS sdk_conversation_command;
DROP TABLE IF EXISTS sdk_conversation_new;

CREATE TABLE sdk_conversation_new (
    session_id TEXT PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    principal_id TEXT NOT NULL,
    principal_type TEXT NOT NULL,
    display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 256),
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    last_activity_at INTEGER NOT NULL CHECK (last_activity_at >= created_at),
    revision INTEGER NOT NULL CHECK (revision >= 0),
    FOREIGN KEY (session_id) REFERENCES session(session_id)
) STRICT;

INSERT INTO sdk_conversation_new (
    session_id, tenant_id, principal_id, principal_type, display_name,
    created_at, last_activity_at, revision
)
SELECT session_id, tenant_id, principal_id, principal_type, display_name,
       created_at, last_activity_at, revision
FROM sdk_conversation;

DROP TABLE sdk_conversation;
ALTER TABLE sdk_conversation_new RENAME TO sdk_conversation;

CREATE INDEX IF NOT EXISTS idx_sdk_conversation_scope_activity
ON sdk_conversation(
    tenant_id,
    principal_id,
    principal_type,
    last_activity_at DESC,
    session_id DESC
);
