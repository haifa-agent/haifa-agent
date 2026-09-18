-- Haifa Agent Runtime Store V1. UTC instants are epoch milliseconds.
-- Every encoded BLOB has an adjacent schema version and SHA-256 column.

CREATE TABLE session (
    session_id TEXT PRIMARY KEY,
    schema_version TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    owner_principal_id TEXT NOT NULL,
    owner_principal_type TEXT NOT NULL,
    project_id TEXT,
    scope TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    closed_at INTEGER,
    version INTEGER NOT NULL CHECK (version >= 0),
    metadata_schema_version TEXT NOT NULL,
    metadata_payload BLOB NOT NULL,
    metadata_hash TEXT NOT NULL,
    CHECK (closed_at IS NULL OR closed_at >= created_at)
) STRICT;

CREATE INDEX idx_session_tenant_owner
ON session(tenant_id, owner_principal_id);

CREATE TABLE configuration_snapshot (
    configuration_ref TEXT PRIMARY KEY,
    schema_version TEXT NOT NULL,
    definition_id TEXT NOT NULL,
    definition_version TEXT NOT NULL,
    profile_id TEXT NOT NULL,
    profile_version TEXT NOT NULL,
    run_type TEXT NOT NULL,
    content_schema_version TEXT NOT NULL,
    content_payload BLOB NOT NULL,
    content_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL CHECK (created_at >= 0)
) STRICT;

CREATE TABLE run (
    run_id TEXT PRIMARY KEY,
    schema_version TEXT NOT NULL,
    root_run_id TEXT NOT NULL,
    parent_run_id TEXT,
    session_id TEXT NOT NULL,
    project_id TEXT,
    tenant_id TEXT NOT NULL,
    principal_id TEXT NOT NULL,
    principal_type TEXT NOT NULL,
    agent_definition_id TEXT NOT NULL,
    agent_definition_version TEXT NOT NULL,
    product_profile_id TEXT NOT NULL,
    product_profile_version TEXT NOT NULL,
    run_type TEXT NOT NULL,
    invocation_mode TEXT NOT NULL,
    depth INTEGER NOT NULL CHECK (depth >= 0),
    objective TEXT NOT NULL,
    budget_max_input_tokens INTEGER NOT NULL CHECK (budget_max_input_tokens >= 0),
    budget_max_output_tokens INTEGER NOT NULL CHECK (budget_max_output_tokens >= 0),
    budget_max_cached_input_tokens INTEGER NOT NULL CHECK (budget_max_cached_input_tokens >= 0),
    budget_max_tool_calls INTEGER NOT NULL CHECK (budget_max_tool_calls >= 0),
    budget_max_model_calls INTEGER NOT NULL CHECK (budget_max_model_calls >= 0),
    budget_max_child_runs INTEGER NOT NULL CHECK (budget_max_child_runs >= 0),
    budget_max_cost_currency TEXT NOT NULL,
    budget_max_cost_minor_units INTEGER NOT NULL CHECK (budget_max_cost_minor_units >= 0),
    limit_max_iterations INTEGER NOT NULL CHECK (limit_max_iterations > 0),
    limit_max_depth INTEGER NOT NULL CHECK (limit_max_depth >= 0),
    limit_max_parallel_children INTEGER NOT NULL CHECK (limit_max_parallel_children > 0),
    limit_max_wall_time_millis INTEGER NOT NULL CHECK (limit_max_wall_time_millis > 0),
    limit_max_idle_time_millis INTEGER NOT NULL CHECK (limit_max_idle_time_millis > 0),
    configuration_ref TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN (
        'PENDING', 'QUEUED', 'RUNNING', 'SUSPENDING', 'SUSPENDED',
        'WAITING_INTERACTION', 'WAITING_APPROVAL', 'COMPLETING',
        'COMPLETED', 'FAILED', 'CANCELLED', 'TIMEOUT'
    )),
    usage_input_tokens INTEGER NOT NULL CHECK (usage_input_tokens >= 0),
    usage_output_tokens INTEGER NOT NULL CHECK (usage_output_tokens >= 0),
    usage_cached_input_tokens INTEGER NOT NULL CHECK (usage_cached_input_tokens >= 0),
    usage_model_calls INTEGER NOT NULL CHECK (usage_model_calls >= 0),
    usage_tool_calls INTEGER NOT NULL CHECK (usage_tool_calls >= 0),
    usage_child_runs INTEGER NOT NULL CHECK (usage_child_runs >= 0),
    usage_cost_minor_units INTEGER NOT NULL CHECK (usage_cost_minor_units >= 0),
    usage_wall_time_millis INTEGER NOT NULL CHECK (usage_wall_time_millis >= 0),
    result_schema_version TEXT,
    result_payload BLOB,
    result_hash TEXT,
    error_schema_version TEXT,
    error_payload BLOB,
    error_hash TEXT,
    waiting_request_id TEXT,
    termination_reason TEXT,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    queued_at INTEGER,
    started_at INTEGER,
    suspended_at INTEGER,
    resumed_at INTEGER,
    completed_at INTEGER,
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    version INTEGER NOT NULL CHECK (version >= 0),
    FOREIGN KEY (session_id) REFERENCES session(session_id),
    FOREIGN KEY (configuration_ref) REFERENCES configuration_snapshot(configuration_ref),
    FOREIGN KEY (root_run_id) REFERENCES run(run_id),
    FOREIGN KEY (parent_run_id) REFERENCES run(run_id),
    CHECK ((result_payload IS NULL) = (result_schema_version IS NULL)),
    CHECK ((result_payload IS NULL) = (result_hash IS NULL)),
    CHECK ((error_payload IS NULL) = (error_schema_version IS NULL)),
    CHECK ((error_payload IS NULL) = (error_hash IS NULL))
) STRICT;

CREATE INDEX idx_run_session_created
ON run(session_id, created_at);

CREATE INDEX idx_run_status_updated
ON run(status, updated_at);

CREATE TABLE execution_attempt (
    attempt_id TEXT PRIMARY KEY,
    schema_version TEXT NOT NULL,
    run_id TEXT NOT NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    status TEXT NOT NULL CHECK (status IN (
        'QUEUED', 'RUNNING', 'SUCCEEDED', 'PAUSED', 'CANCELLED', 'FAILED', 'ABANDONED'
    )),
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    started_at INTEGER,
    heartbeat_at INTEGER,
    completed_at INTEGER,
    worker_id TEXT,
    resumed_from_checkpoint_id TEXT,
    error_schema_version TEXT,
    error_payload BLOB,
    error_hash TEXT,
    version INTEGER NOT NULL CHECK (version >= 0),
    FOREIGN KEY (run_id) REFERENCES run(run_id),
    FOREIGN KEY (resumed_from_checkpoint_id) REFERENCES checkpoint(checkpoint_id),
    UNIQUE (run_id, attempt_number),
    CHECK ((error_payload IS NULL) = (error_schema_version IS NULL)),
    CHECK ((error_payload IS NULL) = (error_hash IS NULL))
) STRICT;

CREATE UNIQUE INDEX uq_active_attempt_per_run
ON execution_attempt(run_id)
WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_attempt_run_number
ON execution_attempt(run_id, attempt_number);

CREATE TABLE session_message (
    message_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    run_id TEXT,
    parent_message_id TEXT,
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    role TEXT NOT NULL CHECK (role IN (
        'SYSTEM', 'DEVELOPER', 'USER', 'ASSISTANT', 'TOOL', 'AGENT', 'RUNTIME'
    )),
    status TEXT NOT NULL CHECK (status IN (
        'CREATED', 'STREAMING', 'COMPLETED', 'FAILED', 'REDACTED', 'DELETED'
    )),
    visibility TEXT NOT NULL CHECK (visibility IN (
        'USER_VISIBLE', 'AGENT_VISIBLE', 'ADMIN_VISIBLE', 'INTERNAL', 'HIDDEN', 'REDACTED'
    )),
    content_schema_version TEXT NOT NULL,
    content_payload BLOB NOT NULL,
    content_hash TEXT NOT NULL,
    metadata_schema_version TEXT NOT NULL,
    metadata_payload BLOB NOT NULL,
    metadata_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    FOREIGN KEY (session_id) REFERENCES session(session_id),
    FOREIGN KEY (run_id) REFERENCES run(run_id),
    FOREIGN KEY (parent_message_id) REFERENCES session_message(message_id),
    UNIQUE (session_id, sequence)
) STRICT;

CREATE INDEX idx_message_run_sequence
ON session_message(run_id, sequence);

CREATE TABLE step (
    step_id TEXT PRIMARY KEY,
    schema_version TEXT NOT NULL,
    run_id TEXT NOT NULL,
    parent_step_id TEXT,
    branch_id TEXT,
    type TEXT NOT NULL,
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    status TEXT NOT NULL CHECK (status IN (
        'PENDING', 'RUNNING', 'WAITING', 'COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED'
    )),
    result_schema_version TEXT,
    result_payload BLOB,
    result_hash TEXT,
    error_schema_version TEXT,
    error_payload BLOB,
    error_hash TEXT,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    started_at INTEGER,
    completed_at INTEGER,
    version INTEGER NOT NULL CHECK (version >= 0),
    FOREIGN KEY (run_id) REFERENCES run(run_id),
    FOREIGN KEY (parent_step_id) REFERENCES step(step_id),
    UNIQUE (run_id, sequence),
    CHECK ((result_payload IS NULL) = (result_schema_version IS NULL)),
    CHECK ((result_payload IS NULL) = (result_hash IS NULL)),
    CHECK ((error_payload IS NULL) = (error_schema_version IS NULL)),
    CHECK ((error_payload IS NULL) = (error_hash IS NULL))
) STRICT;

CREATE TABLE tool_call (
    tool_call_id TEXT PRIMARY KEY,
    schema_version TEXT NOT NULL,
    run_id TEXT NOT NULL,
    step_id TEXT NOT NULL,
    provider_correlation_id TEXT,
    idempotency_key TEXT,
    tool_name TEXT NOT NULL,
    tool_version TEXT NOT NULL,
    arguments_schema_version TEXT NOT NULL,
    arguments_payload BLOB NOT NULL,
    arguments_hash TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN (
        'REQUESTED', 'VALIDATING', 'POLICY_CHECK', 'WAITING_APPROVAL', 'APPROVED',
        'RUNNING', 'COMPLETED', 'FAILED', 'DENIED', 'CANCELLED', 'TIMEOUT'
    )),
    result_schema_version TEXT,
    result_payload BLOB,
    result_hash TEXT,
    error_schema_version TEXT,
    error_payload BLOB,
    error_hash TEXT,
    requested_at INTEGER NOT NULL CHECK (requested_at >= 0),
    started_at INTEGER,
    completed_at INTEGER,
    version INTEGER NOT NULL CHECK (version >= 0),
    FOREIGN KEY (run_id) REFERENCES run(run_id),
    FOREIGN KEY (step_id) REFERENCES step(step_id),
    UNIQUE (run_id, idempotency_key),
    CHECK ((result_payload IS NULL) = (result_schema_version IS NULL)),
    CHECK ((result_payload IS NULL) = (result_hash IS NULL)),
    CHECK ((error_payload IS NULL) = (error_schema_version IS NULL)),
    CHECK ((error_payload IS NULL) = (error_hash IS NULL))
) STRICT;

CREATE INDEX idx_tool_call_run_requested
ON tool_call(run_id, requested_at);

CREATE TABLE plan (
    plan_id TEXT PRIMARY KEY,
    schema_version TEXT NOT NULL,
    run_id TEXT NOT NULL UNIQUE,
    objective TEXT NOT NULL,
    items_schema_version TEXT NOT NULL,
    items_payload BLOB NOT NULL,
    items_hash TEXT NOT NULL,
    revision INTEGER NOT NULL CHECK (revision >= 0),
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    FOREIGN KEY (run_id) REFERENCES run(run_id)
) STRICT;

CREATE TABLE run_output (
    run_id TEXT PRIMARY KEY,
    output_schema_version TEXT NOT NULL,
    output_payload BLOB NOT NULL,
    output_hash TEXT NOT NULL,
    updated_at INTEGER NOT NULL CHECK (updated_at >= 0),
    FOREIGN KEY (run_id) REFERENCES run(run_id)
) STRICT;

CREATE TABLE checkpoint (
    checkpoint_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    step_id TEXT,
    type TEXT NOT NULL CHECK (type IN (
        'AUTOMATIC', 'MANUAL', 'INTERACTION', 'APPROVAL',
        'FAILURE_RECOVERY', 'GRAPH_NODE', 'WORKSPACE_SNAPSHOT'
    )),
    status TEXT NOT NULL CHECK (status IN ('CREATED', 'VERIFIED', 'CORRUPTED')),
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    payload_store_type TEXT NOT NULL,
    payload_location TEXT NOT NULL,
    payload_schema_id TEXT NOT NULL,
    payload_schema_version TEXT NOT NULL,
    state_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    FOREIGN KEY (run_id) REFERENCES run(run_id),
    FOREIGN KEY (step_id) REFERENCES step(step_id),
    UNIQUE (run_id, sequence)
) STRICT;

CREATE TABLE checkpoint_payload (
    checkpoint_id TEXT PRIMARY KEY,
    state_schema_version TEXT NOT NULL,
    state_payload BLOB NOT NULL,
    state_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    FOREIGN KEY (checkpoint_id) REFERENCES checkpoint(checkpoint_id) ON DELETE CASCADE
) STRICT;

CREATE TABLE runtime_event (
    event_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    type TEXT NOT NULL,
    data_schema_version TEXT NOT NULL,
    data_payload BLOB NOT NULL,
    data_hash TEXT NOT NULL,
    occurred_at INTEGER NOT NULL CHECK (occurred_at >= 0),
    FOREIGN KEY (run_id) REFERENCES run(run_id),
    UNIQUE (run_id, sequence)
) STRICT;

CREATE INDEX idx_event_run_occurred
ON runtime_event(run_id, occurred_at);

CREATE TABLE outbox (
    event_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    type TEXT NOT NULL,
    payload_schema_version TEXT NOT NULL,
    payload BLOB NOT NULL,
    payload_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    published_at INTEGER,
    FOREIGN KEY (event_id) REFERENCES runtime_event(event_id),
    FOREIGN KEY (run_id, sequence) REFERENCES runtime_event(run_id, sequence),
    UNIQUE (run_id, sequence)
) STRICT;

CREATE INDEX idx_outbox_pending
ON outbox(published_at, created_at, event_id);

CREATE TABLE outbox_consumer (
    consumer_id TEXT NOT NULL,
    event_id TEXT NOT NULL,
    consumed_at INTEGER NOT NULL CHECK (consumed_at >= 0),
    PRIMARY KEY (consumer_id, event_id),
    FOREIGN KEY (event_id) REFERENCES outbox(event_id) ON DELETE CASCADE
) STRICT;

CREATE TABLE idempotency (
    caller_scope TEXT NOT NULL,
    operation TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    run_id TEXT,
    command_applied INTEGER NOT NULL DEFAULT 0 CHECK (command_applied IN (0, 1)),
    result_schema_version TEXT,
    result_payload BLOB,
    result_hash TEXT,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    PRIMARY KEY (caller_scope, operation, idempotency_key),
    FOREIGN KEY (run_id) REFERENCES run(run_id),
    CHECK ((result_payload IS NULL) = (result_schema_version IS NULL)),
    CHECK ((result_payload IS NULL) = (result_hash IS NULL))
) STRICT;

CREATE TABLE memory_selection (
    run_id TEXT PRIMARY KEY,
    retrieval_policy_version TEXT NOT NULL,
    query_digest TEXT NOT NULL,
    memories_schema_version TEXT NOT NULL,
    memories_payload BLOB NOT NULL,
    memories_hash TEXT NOT NULL,
    updated_at INTEGER NOT NULL CHECK (updated_at >= 0),
    FOREIGN KEY (run_id) REFERENCES run(run_id)
) STRICT;

CREATE TABLE model_continuation (
    continuation_id TEXT PRIMARY KEY,
    continuation_version TEXT NOT NULL,
    continuation_digest TEXT NOT NULL,
    byte_length INTEGER NOT NULL CHECK (byte_length > 0),
    assistant_message_id TEXT NOT NULL UNIQUE,
    run_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    model_call_id TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    model_id TEXT NOT NULL,
    configuration_digest TEXT NOT NULL,
    tool_correlations_schema_version TEXT NOT NULL,
    tool_correlations_payload BLOB NOT NULL,
    tool_correlations_hash TEXT NOT NULL,
    protection_version TEXT NOT NULL,
    nonce_schema_version TEXT NOT NULL,
    nonce_payload BLOB NOT NULL,
    nonce_hash TEXT NOT NULL,
    ciphertext_schema_version TEXT NOT NULL,
    ciphertext_payload BLOB NOT NULL,
    ciphertext_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    FOREIGN KEY (assistant_message_id) REFERENCES session_message(message_id),
    FOREIGN KEY (run_id) REFERENCES run(run_id),
    FOREIGN KEY (session_id) REFERENCES session(session_id)
) STRICT;

CREATE TABLE skill_activation (
    run_id TEXT NOT NULL,
    skill_alias TEXT NOT NULL,
    coordinate TEXT NOT NULL,
    content_digest TEXT NOT NULL,
    reason TEXT NOT NULL,
    requested_by TEXT NOT NULL,
    instruction_bytes INTEGER NOT NULL CHECK (instruction_bytes > 0),
    estimated_tokens INTEGER NOT NULL CHECK (estimated_tokens > 0),
    activation_schema_version TEXT NOT NULL,
    activation_payload BLOB NOT NULL,
    activation_hash TEXT NOT NULL,
    activated_at INTEGER NOT NULL CHECK (activated_at >= 0),
    PRIMARY KEY (run_id, skill_alias),
    FOREIGN KEY (run_id) REFERENCES run(run_id)
) STRICT;

CREATE TABLE skill_resource_usage (
    run_id TEXT PRIMARY KEY,
    bytes_read INTEGER NOT NULL CHECK (bytes_read >= 0),
    updated_at INTEGER NOT NULL CHECK (updated_at >= 0),
    FOREIGN KEY (run_id) REFERENCES run(run_id)
) STRICT;

CREATE TABLE conversation_summary (
    summary_id TEXT NOT NULL,
    summary_version INTEGER NOT NULL CHECK (summary_version > 0),
    session_id TEXT NOT NULL,
    covered_from INTEGER NOT NULL CHECK (covered_from > 0),
    covered_through INTEGER NOT NULL CHECK (covered_through >= covered_from),
    source_hash TEXT NOT NULL,
    content_schema_version TEXT NOT NULL,
    content_payload BLOB NOT NULL,
    content_hash TEXT NOT NULL,
    estimated_tokens INTEGER NOT NULL CHECK (estimated_tokens > 0),
    policy_version TEXT NOT NULL,
    compressor_version TEXT NOT NULL,
    valid INTEGER NOT NULL CHECK (valid IN (0, 1)),
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    PRIMARY KEY (summary_id, summary_version),
    FOREIGN KEY (session_id) REFERENCES session(session_id),
    UNIQUE (session_id, summary_version)
) STRICT;

CREATE INDEX idx_summary_latest_valid
ON conversation_summary(session_id, valid, summary_version DESC);

CREATE TABLE tool_result_asset (
    asset_ref TEXT PRIMARY KEY,
    tool_call_id TEXT NOT NULL,
    result_schema_version TEXT NOT NULL,
    result_payload BLOB NOT NULL,
    result_hash TEXT NOT NULL,
    byte_length INTEGER NOT NULL CHECK (byte_length > 0),
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    FOREIGN KEY (tool_call_id) REFERENCES tool_call(tool_call_id)
) STRICT;

CREATE TABLE tool_journal (
    run_id TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN (
        'INTENT_RECORDED', 'DISPATCHED', 'ACKNOWLEDGED', 'PENDING_RESULT',
        'COMPLETED', 'FAILED', 'OUTCOME_UNKNOWN'
    )),
    tool_idempotency TEXT NOT NULL CHECK (tool_idempotency IN (
        'PURE', 'IDEMPOTENT', 'IDEMPOTENT_WITH_KEY', 'NON_IDEMPOTENT', 'UNKNOWN'
    )),
    result_schema_version TEXT,
    result_payload BLOB,
    result_hash TEXT,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    PRIMARY KEY (run_id, idempotency_key),
    FOREIGN KEY (run_id) REFERENCES run(run_id),
    CHECK ((result_payload IS NULL) = (result_schema_version IS NULL)),
    CHECK ((result_payload IS NULL) = (result_hash IS NULL))
) STRICT;

CREATE INDEX idx_tool_journal_uncertain
ON tool_journal(run_id, state);

CREATE TABLE interaction_request (
    request_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    principal_id TEXT NOT NULL,
    principal_type TEXT NOT NULL,
    type TEXT NOT NULL,
    prompt TEXT NOT NULL,
    approval INTEGER NOT NULL CHECK (approval IN (0, 1)),
    target_type TEXT NOT NULL,
    target_schema_version TEXT NOT NULL,
    target_payload BLOB NOT NULL,
    target_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    expires_at INTEGER NOT NULL CHECK (expires_at > created_at),
    FOREIGN KEY (run_id) REFERENCES run(run_id)
) STRICT;

CREATE INDEX idx_interaction_request_pending
ON interaction_request(run_id, expires_at, created_at);

CREATE TABLE interaction_response (
    response_id TEXT PRIMARY KEY,
    request_id TEXT NOT NULL UNIQUE,
    run_id TEXT NOT NULL,
    response_type TEXT NOT NULL CHECK (response_type IN ('APPROVE', 'REJECT', 'CLARIFY')),
    inputs_schema_version TEXT NOT NULL,
    inputs_payload BLOB NOT NULL,
    inputs_hash TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    responded_at INTEGER NOT NULL CHECK (responded_at >= 0),
    resolved_at INTEGER NOT NULL CHECK (resolved_at >= responded_at),
    FOREIGN KEY (request_id) REFERENCES interaction_request(request_id),
    FOREIGN KEY (run_id) REFERENCES run(run_id),
    UNIQUE (run_id, idempotency_key)
) STRICT;

CREATE TABLE interaction_application (
    request_id TEXT PRIMARY KEY,
    resolution_applied INTEGER NOT NULL DEFAULT 0 CHECK (resolution_applied IN (0, 1)),
    applied_at INTEGER,
    FOREIGN KEY (request_id) REFERENCES interaction_request(request_id) ON DELETE CASCADE,
    CHECK ((resolution_applied = 0 AND applied_at IS NULL)
        OR (resolution_applied = 1 AND applied_at IS NOT NULL))
) STRICT;
