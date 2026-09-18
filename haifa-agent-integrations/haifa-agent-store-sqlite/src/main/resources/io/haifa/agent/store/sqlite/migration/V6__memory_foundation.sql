CREATE TABLE memory_candidate (
    candidate_id TEXT PRIMARY KEY NOT NULL,
    request_key_digest TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    scope_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    visibility TEXT NOT NULL,
    security_label_bits INTEGER NOT NULL,
    kind TEXT NOT NULL,
    subject_key TEXT NOT NULL,
    normalized_digest TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    revision INTEGER NOT NULL CHECK (revision >= 0),
    updated_at INTEGER NOT NULL,
    payload_schema_version INTEGER NOT NULL CHECK (payload_schema_version = 1),
    payload_type TEXT NOT NULL CHECK (payload_type = 'memory-candidate'),
    payload_hash TEXT NOT NULL,
    payload BLOB NOT NULL,
    UNIQUE (tenant_id, owner_id, scope_type, target_id, visibility, request_key_digest)
) STRICT;

CREATE INDEX idx_memory_candidate_scope_page
    ON memory_candidate(tenant_id, owner_id, scope_type, target_id, visibility, updated_at DESC, candidate_id DESC);
CREATE INDEX idx_memory_candidate_pending_subject
    ON memory_candidate(
        tenant_id, owner_id, scope_type, target_id, visibility, kind, normalized_digest, subject_key)
    WHERE status = 'PENDING';

CREATE TABLE memory_record (
    memory_id TEXT NOT NULL,
    memory_version INTEGER NOT NULL CHECK (memory_version > 0),
    tenant_id TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    scope_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    visibility TEXT NOT NULL,
    security_label_bits INTEGER NOT NULL,
    kind TEXT NOT NULL,
    subject_key TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'INVALIDATED')),
    normalized_digest TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    payload_schema_version INTEGER NOT NULL CHECK (payload_schema_version = 1),
    payload_type TEXT NOT NULL CHECK (payload_type = 'memory-record'),
    payload_hash TEXT NOT NULL,
    payload BLOB NOT NULL,
    PRIMARY KEY (memory_id, memory_version)
) STRICT;

CREATE UNIQUE INDEX uq_memory_record_active_id
    ON memory_record(memory_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_memory_record_active_subject
    ON memory_record(tenant_id, owner_id, scope_type, target_id, visibility, kind, subject_key)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_memory_record_scope_page
    ON memory_record(tenant_id, owner_id, scope_type, target_id, visibility, updated_at DESC, memory_id DESC, memory_version DESC);

CREATE TABLE memory_audit_event (
    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
    operation TEXT NOT NULL,
    candidate_id TEXT,
    memory_id TEXT,
    memory_version INTEGER,
    tenant_id TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    scope_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    visibility TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    safe_attributes_json TEXT NOT NULL,
    occurred_at INTEGER NOT NULL,
    idempotency_key_digest TEXT,
    request_digest TEXT,
    candidate_revision INTEGER,
    CHECK ((idempotency_key_digest IS NULL) = (request_digest IS NULL))
) STRICT;

CREATE UNIQUE INDEX uq_memory_audit_idempotency
    ON memory_audit_event(
        tenant_id, owner_id, scope_type, target_id, visibility, operation, idempotency_key_digest)
    WHERE idempotency_key_digest IS NOT NULL;
CREATE INDEX idx_memory_audit_scope_time
    ON memory_audit_event(tenant_id, owner_id, scope_type, target_id, visibility, occurred_at);
