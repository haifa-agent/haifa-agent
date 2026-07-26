-- Task 02: stable Runtime Journal identity/range reads and durable HITL/Steer state.
-- Existing V1-V3 data is upgraded in place; prior migrations remain immutable.

ALTER TABLE runtime_event ADD COLUMN event_schema_version TEXT NOT NULL DEFAULT '1';
ALTER TABLE runtime_event ADD COLUMN correlation_id TEXT;
ALTER TABLE runtime_event ADD COLUMN causation_id TEXT;

CREATE INDEX idx_runtime_event_run_sequence
ON runtime_event(run_id, sequence);

CREATE TABLE runtime_event_stream (
    run_id TEXT PRIMARY KEY,
    head_sequence INTEGER NOT NULL CHECK (head_sequence >= 0),
    earliest_sequence INTEGER NOT NULL CHECK (earliest_sequence >= 1),
    updated_at INTEGER NOT NULL CHECK (updated_at >= 0),
    FOREIGN KEY (run_id) REFERENCES run(run_id) ON DELETE CASCADE,
    CHECK (earliest_sequence <= head_sequence + 1)
) STRICT;

INSERT INTO runtime_event_stream(run_id, head_sequence, earliest_sequence, updated_at)
SELECT run_id, MAX(sequence), MIN(sequence), MAX(occurred_at)
FROM runtime_event
GROUP BY run_id;

ALTER TABLE interaction_request ADD COLUMN revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0);
ALTER TABLE interaction_request ADD COLUMN kind TEXT NOT NULL DEFAULT 'unknown';
ALTER TABLE interaction_request ADD COLUMN state TEXT NOT NULL DEFAULT 'PENDING'
    CHECK (state IN ('PENDING', 'RESPONDED', 'APPLIED', 'EXPIRED', 'CANCELLED', 'INVALIDATED'));
ALTER TABLE interaction_request ADD COLUMN expiration_outcome TEXT NOT NULL DEFAULT 'FAIL_RUN'
    CHECK (expiration_outcome IN ('FAIL_RUN', 'CANCEL_RUN', 'RETURN_TO_AGENT'));
ALTER TABLE interaction_request ADD COLUMN state_reason_code TEXT;
ALTER TABLE interaction_request ADD COLUMN state_changed_at INTEGER;

UPDATE interaction_request
SET kind = CASE
        WHEN approval = 1 THEN 'approval'
        WHEN type IN ('clarification', 'confirmation', 'selection', 'input-required',
                      'artifact-review', 'conflict-resolution') THEN type
        ELSE 'unknown'
    END,
    expiration_outcome = CASE WHEN approval = 1 THEN 'CANCEL_RUN' ELSE 'FAIL_RUN' END,
    state = CASE
        WHEN EXISTS (
            SELECT 1 FROM interaction_application application
            WHERE application.request_id = interaction_request.request_id
              AND application.resolution_applied = 1
        ) THEN 'APPLIED'
        WHEN EXISTS (
            SELECT 1 FROM interaction_response response
            WHERE response.request_id = interaction_request.request_id
        ) THEN 'RESPONDED'
        ELSE 'PENDING'
    END,
    revision = CASE
        WHEN EXISTS (
            SELECT 1 FROM interaction_application application
            WHERE application.request_id = interaction_request.request_id
              AND application.resolution_applied = 1
        ) THEN 2
        WHEN EXISTS (
            SELECT 1 FROM interaction_response response
            WHERE response.request_id = interaction_request.request_id
        ) THEN 1
        ELSE 0
    END,
    state_changed_at = COALESCE(
        (SELECT application.applied_at FROM interaction_application application
         WHERE application.request_id = interaction_request.request_id
           AND application.resolution_applied = 1),
        (SELECT response.resolved_at FROM interaction_response response
         WHERE response.request_id = interaction_request.request_id)
    );

-- V1 permitted more than one unresolved request for a Run. Retain the newest and
-- invalidate older requests deterministically before adding the authoritative constraint.
UPDATE interaction_request
SET state = 'INVALIDATED',
    revision = revision + 1,
    state_reason_code = 'MIGRATION_SUPERSEDED_PENDING',
    state_changed_at = created_at
WHERE state = 'PENDING'
  AND EXISTS (
      SELECT 1
      FROM interaction_request newer
      WHERE newer.run_id = interaction_request.run_id
        AND newer.state = 'PENDING'
        AND (
            newer.created_at > interaction_request.created_at
            OR (newer.created_at = interaction_request.created_at
                AND newer.request_id > interaction_request.request_id)
        )
  );

CREATE UNIQUE INDEX uq_interaction_pending_per_run
ON interaction_request(run_id)
WHERE state = 'PENDING';

CREATE INDEX idx_interaction_due
ON interaction_request(state, expires_at, request_id);

ALTER TABLE interaction_response ADD COLUMN action TEXT NOT NULL DEFAULT 'submit';
ALTER TABLE interaction_response ADD COLUMN expected_revision INTEGER NOT NULL DEFAULT 0 CHECK (expected_revision >= 0);
ALTER TABLE interaction_response ADD COLUMN caller_scope TEXT NOT NULL DEFAULT '';
ALTER TABLE interaction_response ADD COLUMN canonical_digest TEXT NOT NULL DEFAULT '';
ALTER TABLE interaction_response ADD COLUMN responder_tenant_id TEXT NOT NULL DEFAULT '';
ALTER TABLE interaction_response ADD COLUMN responder_principal_id TEXT NOT NULL DEFAULT '';
ALTER TABLE interaction_response ADD COLUMN responder_principal_type TEXT NOT NULL DEFAULT '';
ALTER TABLE interaction_response ADD COLUMN receipt_status TEXT NOT NULL DEFAULT 'ACCEPTED'
    CHECK (receipt_status IN ('ACCEPTED', 'DUPLICATE', 'APPLIED'));

UPDATE interaction_response
SET action = CASE response_type
        WHEN 'APPROVE' THEN 'approve'
        WHEN 'REJECT' THEN 'reject'
        ELSE 'submit'
    END,
    caller_scope = (
        SELECT request.tenant_id || '|' || request.principal_type || '|' || request.principal_id
        FROM interaction_request request
        WHERE request.request_id = interaction_response.request_id
    ),
    responder_tenant_id = COALESCE(
        (SELECT metadata.responder_tenant_id
         FROM approval_response_metadata metadata
         WHERE metadata.response_id = interaction_response.response_id),
        (SELECT request.tenant_id FROM interaction_request request
         WHERE request.request_id = interaction_response.request_id)
    ),
    responder_principal_id = COALESCE(
        (SELECT metadata.responder_principal_id
         FROM approval_response_metadata metadata
         WHERE metadata.response_id = interaction_response.response_id),
        (SELECT request.principal_id FROM interaction_request request
         WHERE request.request_id = interaction_response.request_id)
    ),
    responder_principal_type = COALESCE(
        (SELECT metadata.responder_principal_type
         FROM approval_response_metadata metadata
         WHERE metadata.response_id = interaction_response.response_id),
        (SELECT request.principal_type FROM interaction_request request
         WHERE request.request_id = interaction_response.request_id)
    );

CREATE UNIQUE INDEX uq_interaction_response_idempotency
ON interaction_response(caller_scope, request_id, idempotency_key);

CREATE TABLE run_input (
    input_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    expected_run_version INTEGER,
    contents_schema_version TEXT NOT NULL,
    contents_payload BLOB NOT NULL,
    contents_hash TEXT NOT NULL,
    caller_scope TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    canonical_digest TEXT NOT NULL CHECK (length(canonical_digest) = 64),
    submitted_at INTEGER NOT NULL CHECK (submitted_at >= 0),
    accepted_at INTEGER NOT NULL CHECK (accepted_at >= submitted_at),
    status TEXT NOT NULL CHECK (status IN ('ACCEPTED', 'APPLIED', 'REJECTED')),
    applied_at INTEGER,
    attempt_id TEXT,
    iteration INTEGER,
    reason_code TEXT,
    FOREIGN KEY (run_id) REFERENCES run(run_id),
    FOREIGN KEY (attempt_id) REFERENCES execution_attempt(attempt_id),
    UNIQUE (caller_scope, run_id, idempotency_key),
    CHECK (expected_run_version IS NULL OR expected_run_version >= 0),
    CHECK (iteration IS NULL OR iteration >= 0),
    CHECK (
        (status = 'ACCEPTED' AND applied_at IS NULL AND attempt_id IS NULL AND iteration IS NULL)
        OR
        (status = 'APPLIED' AND applied_at IS NOT NULL AND attempt_id IS NOT NULL AND iteration IS NOT NULL)
        OR
        (status = 'REJECTED' AND reason_code IS NOT NULL)
    )
) STRICT;

CREATE INDEX idx_run_input_pending
ON run_input(run_id, status, accepted_at, input_id);

ALTER TABLE idempotency ADD COLUMN target_type TEXT;
ALTER TABLE idempotency ADD COLUMN target_id TEXT;
ALTER TABLE idempotency ADD COLUMN request_digest TEXT;
ALTER TABLE idempotency ADD COLUMN expires_at INTEGER;
