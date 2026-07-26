CREATE TABLE policy_snapshot (
    snapshot_id TEXT PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    product_id TEXT NOT NULL,
    approval_mode TEXT NOT NULL CHECK (approval_mode IN ('ASK', 'AUTO', 'DENY')),
    product_profile_ref TEXT NOT NULL,
    project_trust_ref TEXT,
    rules_schema_version TEXT NOT NULL,
    rules_payload BLOB NOT NULL,
    rules_hash TEXT NOT NULL CHECK (length(rules_hash) = 71),
    content_digest TEXT NOT NULL,
    created_at INTEGER NOT NULL CHECK (created_at >= 0)
);

CREATE TABLE policy_decision (
    decision_id TEXT PRIMARY KEY,
    snapshot_id TEXT NOT NULL REFERENCES policy_snapshot(snapshot_id),
    tenant_id TEXT NOT NULL,
    principal_id TEXT NOT NULL,
    principal_type TEXT NOT NULL,
    product_id TEXT NOT NULL,
    project_ref TEXT,
    session_ref TEXT,
    run_id TEXT,
    attempt_id TEXT,
    capability TEXT NOT NULL,
    operation TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_ref TEXT NOT NULL,
    resource_digest TEXT,
    effect TEXT NOT NULL CHECK (effect IN ('ALLOW', 'ASK', 'DENY')),
    challenge TEXT CHECK (challenge IS NULL OR challenge IN ('APPROVAL', 'REAUTHENTICATE')),
    reason_code TEXT NOT NULL,
    safe_explanation TEXT NOT NULL,
    matched_rule_id TEXT,
    matched_rule_version TEXT,
    request_digest TEXT NOT NULL,
    request_schema_version TEXT NOT NULL,
    request_payload BLOB NOT NULL,
    request_hash TEXT NOT NULL CHECK (length(request_hash) = 71),
    decided_at INTEGER NOT NULL CHECK (decided_at >= 0),
    CHECK ((effect = 'ASK' AND challenge IS NOT NULL) OR (effect <> 'ASK' AND challenge IS NULL))
);

CREATE INDEX idx_policy_decision_run ON policy_decision(run_id, decided_at, decision_id);
CREATE INDEX idx_policy_decision_session ON policy_decision(session_ref, decided_at, decision_id);
CREATE INDEX idx_policy_decision_target ON policy_decision(resource_type, resource_ref, decided_at);

CREATE TABLE approval_request_metadata (
    request_id TEXT PRIMARY KEY REFERENCES interaction_request(request_id) ON DELETE CASCADE,
    decision_id TEXT NOT NULL REFERENCES policy_decision(decision_id),
    semantics TEXT NOT NULL CHECK (semantics IN ('CAPABILITY_CONFIRMATION', 'BUSINESS_AUTHORIZATION')),
    challenge TEXT NOT NULL CHECK (challenge IN ('APPROVAL', 'REAUTHENTICATE')),
    requester_tenant_id TEXT NOT NULL,
    requester_principal_id TEXT NOT NULL,
    requester_principal_type TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    target_version TEXT NOT NULL,
    target_operation TEXT NOT NULL,
    target_digest TEXT NOT NULL,
    target_safe_summary TEXT NOT NULL,
    authority_provider_id TEXT,
    authority_requirement_id TEXT,
    authority_requirement_version TEXT,
    external_correlation_ref TEXT,
    metadata_schema_version TEXT NOT NULL,
    metadata_payload BLOB NOT NULL,
    metadata_hash TEXT NOT NULL CHECK (length(metadata_hash) = 71)
);

CREATE INDEX idx_approval_request_decision ON approval_request_metadata(decision_id);

CREATE TABLE approval_response_metadata (
    response_id TEXT PRIMARY KEY REFERENCES interaction_response(response_id) ON DELETE CASCADE,
    responder_tenant_id TEXT NOT NULL,
    responder_principal_id TEXT NOT NULL,
    responder_principal_type TEXT NOT NULL,
    authority_outcome TEXT NOT NULL CHECK (authority_outcome IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    authority_reason_code TEXT NOT NULL,
    target_outcome TEXT NOT NULL CHECK (target_outcome IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    target_reason_code TEXT NOT NULL,
    selected_reuse_scope TEXT CHECK (
        selected_reuse_scope IS NULL OR selected_reuse_scope IN ('ONCE', 'SESSION', 'PROJECT')
    ),
    validation_digest TEXT NOT NULL CHECK (length(validation_digest) = 64)
);

CREATE TABLE policy_authorization_evidence (
    decision_id TEXT PRIMARY KEY REFERENCES policy_decision(decision_id) ON DELETE CASCADE,
    request_digest TEXT NOT NULL,
    requester_tenant_id TEXT NOT NULL,
    requester_principal_id TEXT NOT NULL,
    requester_principal_type TEXT NOT NULL,
    responder_tenant_id TEXT NOT NULL,
    responder_principal_id TEXT NOT NULL,
    responder_principal_type TEXT NOT NULL,
    approved_at INTEGER NOT NULL CHECK (approved_at >= 0),
    expires_at INTEGER NOT NULL CHECK (expires_at > approved_at)
);

CREATE TABLE project_trust (
    trust_id TEXT PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    principal_id TEXT NOT NULL,
    principal_type TEXT NOT NULL,
    project_ref TEXT NOT NULL,
    canonical_project_identity TEXT NOT NULL,
    trusted_root_identity TEXT NOT NULL,
    authorization_configuration_digest TEXT NOT NULL,
    product_profile_ref TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('TRUSTED', 'REVOKED')),
    confirmed_at INTEGER NOT NULL CHECK (confirmed_at >= 0),
    expires_at INTEGER,
    revoked_at INTEGER,
    revocation_reason_code TEXT,
    version INTEGER NOT NULL CHECK (version >= 0),
    CHECK (expires_at IS NULL OR expires_at > confirmed_at),
    CHECK (revoked_at IS NULL OR revoked_at >= confirmed_at),
    CHECK (
        (state = 'TRUSTED' AND revoked_at IS NULL AND revocation_reason_code IS NULL)
        OR
        (state = 'REVOKED' AND revoked_at IS NOT NULL AND revocation_reason_code IS NOT NULL)
    )
);

CREATE INDEX idx_project_trust_lookup
    ON project_trust(tenant_id, principal_id, principal_type, project_ref, state, expires_at);

CREATE TABLE approval_grant (
    grant_id TEXT PRIMARY KEY,
    semantics TEXT NOT NULL CHECK (semantics = 'CAPABILITY_CONFIRMATION'),
    reuse_scope TEXT NOT NULL CHECK (reuse_scope IN ('ONCE', 'SESSION', 'PROJECT')),
    tenant_id TEXT NOT NULL,
    requester_principal_id TEXT NOT NULL,
    requester_principal_type TEXT NOT NULL,
    product_id TEXT NOT NULL,
    capability TEXT NOT NULL,
    operation TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    target_version TEXT NOT NULL,
    target_operation TEXT NOT NULL,
    target_digest TEXT NOT NULL,
    target_safe_summary TEXT NOT NULL,
    session_ref TEXT,
    project_ref TEXT,
    project_trust_ref TEXT REFERENCES project_trust(trust_id),
    authorization_configuration_digest TEXT,
    source_decision_id TEXT NOT NULL REFERENCES policy_decision(decision_id),
    source_approval_request_id TEXT NOT NULL REFERENCES interaction_request(request_id),
    source_approval_response_id TEXT NOT NULL REFERENCES interaction_response(response_id),
    responder_tenant_id TEXT NOT NULL,
    responder_principal_id TEXT NOT NULL,
    responder_principal_type TEXT NOT NULL,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    expires_at INTEGER,
    revoked_at INTEGER,
    revocation_reason_code TEXT,
    consumed_at INTEGER,
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'REVOKED', 'CONSUMED')),
    version INTEGER NOT NULL CHECK (version >= 0),
    CHECK (expires_at IS NULL OR expires_at > created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at),
    CHECK (reuse_scope <> 'SESSION' OR session_ref IS NOT NULL),
    CHECK (
        reuse_scope <> 'PROJECT'
        OR (project_ref IS NOT NULL AND project_trust_ref IS NOT NULL
            AND authorization_configuration_digest IS NOT NULL)
    ),
    CHECK (
        (state = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason_code IS NULL AND consumed_at IS NULL)
        OR
        (state = 'REVOKED' AND revoked_at IS NOT NULL
            AND revocation_reason_code IS NOT NULL AND consumed_at IS NULL)
        OR
        (state = 'CONSUMED' AND reuse_scope = 'ONCE'
            AND consumed_at IS NOT NULL AND revoked_at IS NULL AND revocation_reason_code IS NULL)
    )
);

CREATE INDEX idx_approval_grant_candidates
    ON approval_grant(
        tenant_id, requester_principal_id, requester_principal_type,
        capability, operation, target_type, state, expires_at
    );
