-- Provider-neutral Workflow durability. SQLite is the only authoritative checkpoint.

CREATE TABLE workflow_run (
    workflow_run_id TEXT PRIMARY KEY,
    definition_id TEXT NOT NULL,
    definition_version INTEGER NOT NULL CHECK (definition_version > 0),
    definition_digest TEXT NOT NULL,
    adapter_coordinate TEXT NOT NULL,
    adapter_version TEXT NOT NULL,
    adapter_configuration_digest TEXT NOT NULL,
    state_codec_version INTEGER NOT NULL CHECK (state_codec_version > 0),
    status TEXT NOT NULL CHECK (status IN ('RUNNING','WAITING','COMPLETED','FAILED','CANCELLED','TIMED_OUT')),
    revision INTEGER NOT NULL CHECK (revision > 0),
    storage_version INTEGER NOT NULL CHECK (storage_version > 0),
    event_sequence INTEGER NOT NULL CHECK (event_sequence >= 0),
    current_node_id TEXT,
    state_schema_version INTEGER NOT NULL CHECK (state_schema_version = 1),
    state_payload BLOB NOT NULL,
    state_hash TEXT NOT NULL CHECK (length(state_hash) = 64),
    control_schema_version INTEGER NOT NULL CHECK (control_schema_version = 1),
    control_payload BLOB NOT NULL,
    control_hash TEXT NOT NULL CHECK (length(control_hash) = 64),
    failure_code TEXT,
    failure_operation TEXT,
    failure_node_id TEXT,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)),
    CHECK ((failure_code IS NULL) = (failure_operation IS NULL))
) STRICT;

CREATE INDEX idx_workflow_run_recovery ON workflow_run(status, updated_at, workflow_run_id);

CREATE TABLE workflow_node_attempt (
    workflow_run_id TEXT NOT NULL REFERENCES workflow_run(workflow_run_id),
    attempt_sequence INTEGER NOT NULL CHECK (attempt_sequence > 0),
    node_id TEXT NOT NULL,
    attempt INTEGER NOT NULL CHECK (attempt > 0),
    status TEXT NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED','OUTCOME_UNKNOWN')),
    agent_run_id TEXT REFERENCES run(run_id),
    failure_code TEXT,
    started_at INTEGER NOT NULL CHECK (started_at >= 0),
    finished_at INTEGER,
    pending_delta_schema_version INTEGER,
    pending_delta_payload BLOB,
    pending_delta_hash TEXT,
    PRIMARY KEY (workflow_run_id, node_id, attempt),
    UNIQUE (workflow_run_id, attempt_sequence),
    CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CHECK ((status IN ('FAILED','OUTCOME_UNKNOWN')) = (failure_code IS NOT NULL)),
    CHECK ((pending_delta_payload IS NULL) = (pending_delta_schema_version IS NULL)),
    CHECK ((pending_delta_payload IS NULL) = (pending_delta_hash IS NULL)),
    CHECK (pending_delta_hash IS NULL OR length(pending_delta_hash) = 64)
) STRICT;

CREATE UNIQUE INDEX uq_workflow_active_attempt
    ON workflow_node_attempt(workflow_run_id) WHERE status = 'RUNNING';
CREATE INDEX idx_workflow_attempt_agent_run ON workflow_node_attempt(agent_run_id) WHERE agent_run_id IS NOT NULL;

CREATE TABLE workflow_wait (
    workflow_run_id TEXT PRIMARY KEY REFERENCES workflow_run(workflow_run_id),
    wait_id TEXT NOT NULL UNIQUE,
    node_id TEXT NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    created_at INTEGER NOT NULL CHECK (created_at >= 0)
) STRICT;

CREATE TABLE workflow_checkpoint (
    checkpoint_id TEXT PRIMARY KEY,
    workflow_run_id TEXT NOT NULL UNIQUE REFERENCES workflow_run(workflow_run_id),
    revision INTEGER NOT NULL CHECK (revision > 0),
    resume_node_id TEXT NOT NULL,
    state_schema_version INTEGER NOT NULL CHECK (state_schema_version = 1),
    state_payload BLOB NOT NULL,
    state_hash TEXT NOT NULL CHECK (length(state_hash) = 64),
    created_at INTEGER NOT NULL CHECK (created_at >= 0)
) STRICT;

CREATE TABLE workflow_event (
    workflow_run_id TEXT NOT NULL REFERENCES workflow_run(workflow_run_id),
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    type TEXT NOT NULL,
    node_id TEXT,
    attributes_schema_version INTEGER NOT NULL CHECK (attributes_schema_version = 1),
    attributes_payload BLOB NOT NULL,
    attributes_hash TEXT NOT NULL CHECK (length(attributes_hash) = 64),
    occurred_at INTEGER NOT NULL CHECK (occurred_at >= 0),
    PRIMARY KEY (workflow_run_id, sequence)
) STRICT;

CREATE TABLE workflow_outbox (
    workflow_run_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    published_at INTEGER,
    publish_attempts INTEGER NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    PRIMARY KEY (workflow_run_id, sequence),
    FOREIGN KEY (workflow_run_id, sequence) REFERENCES workflow_event(workflow_run_id, sequence)
) STRICT;

CREATE INDEX idx_workflow_outbox_pending
    ON workflow_outbox(published_at, workflow_run_id, sequence);

CREATE TABLE workflow_command (
    operation TEXT NOT NULL,
    command_scope TEXT NOT NULL,
    idempotency_key_digest TEXT NOT NULL CHECK (length(idempotency_key_digest) = 64),
    request_digest TEXT NOT NULL CHECK (length(request_digest) = 64),
    workflow_run_id TEXT NOT NULL REFERENCES workflow_run(workflow_run_id),
    result_schema_version INTEGER NOT NULL CHECK (result_schema_version = 1),
    result_payload BLOB NOT NULL,
    result_hash TEXT NOT NULL CHECK (length(result_hash) = 64),
    PRIMARY KEY (operation, command_scope, idempotency_key_digest)
) STRICT;
