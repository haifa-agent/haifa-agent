-- Memory direct CRUD: one authoritative memory table plus per-scope clear watermarks.
-- Clean cut: the audited Personal Assistant databases held no memory rows. Live user data (ACTIVE memories or
-- PENDING candidates) aborts the migration instead of being dropped; derived and retired rows are discarded.
CREATE TEMP TABLE v15_memory_guard (
    live_rows INTEGER NOT NULL,
    CONSTRAINT v15_memory_tables_must_not_hold_live_rows CHECK (live_rows = 0)
);
INSERT INTO v15_memory_guard(live_rows)
SELECT (SELECT count(*) FROM memory_record WHERE status = 'ACTIVE')
     + (SELECT count(*) FROM memory_candidate WHERE status = 'PENDING');
DROP TABLE v15_memory_guard;

DROP TABLE memory_audit_event;
DROP TABLE memory_candidate;
DROP TABLE memory_record;
-- Selections reference retired memory versions; they are a derived per-Run record only.
DELETE FROM memory_selection;

CREATE TABLE memory_record (
    memory_id TEXT PRIMARY KEY NOT NULL,
    tenant_id TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    owner_type TEXT NOT NULL,
    scope_type TEXT NOT NULL CHECK (scope_type IN ('USER', 'AGENT', 'SESSION')),
    target_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    subject_key TEXT NOT NULL,
    content TEXT,
    source_type TEXT,
    source_id TEXT,
    revision INTEGER NOT NULL CHECK (revision > 0),
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    deleted_at INTEGER,
    CHECK ((deleted_at IS NULL) = (content IS NOT NULL)),
    CHECK ((source_type IS NULL) = (source_id IS NULL)),
    UNIQUE (tenant_id, owner_id, owner_type, scope_type, target_id, kind, subject_key)
) STRICT;

CREATE INDEX idx_memory_record_scope_live
    ON memory_record(tenant_id, owner_id, owner_type, scope_type, target_id, updated_at DESC, memory_id DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE memory_scope_clear (
    tenant_id TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    owner_type TEXT NOT NULL,
    scope_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    cleared_at INTEGER NOT NULL CHECK (cleared_at >= 0),
    PRIMARY KEY (tenant_id, owner_id, owner_type, scope_type, target_id)
) STRICT;
