CREATE TABLE coding_workspace_registry (
    project_id TEXT NOT NULL,
    workspace_ref TEXT NOT NULL,
    location_ref TEXT NOT NULL,
    safe_display_name TEXT NOT NULL,
    source TEXT NOT NULL CHECK (source IN ('INITIAL', 'APPROVED_ATTACH', 'APPROVED_WORKTREE_CREATE')),
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED', 'REVOKED')),
    location_nonce BLOB NOT NULL,
    location_ciphertext BLOB NOT NULL,
    location_digest TEXT NOT NULL,
    physical_fingerprint TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    validated_at_ms INTEGER NOT NULL,
    revoked_at_ms INTEGER,
    revocation_reason_code TEXT,
    version INTEGER NOT NULL CHECK (version >= 0),
    PRIMARY KEY (project_id, workspace_ref),
    UNIQUE (project_id, location_ref),
    CHECK ((status = 'ACTIVE' AND revoked_at_ms IS NULL AND revocation_reason_code IS NULL)
        OR (status != 'ACTIVE' AND revoked_at_ms IS NOT NULL AND revocation_reason_code IS NOT NULL))
);

CREATE INDEX idx_coding_workspace_registry_project_status
    ON coding_workspace_registry(project_id, status, workspace_ref);

CREATE TABLE coding_workspace_access (
    tenant_id TEXT NOT NULL,
    principal_type TEXT NOT NULL,
    principal_id TEXT NOT NULL,
    workspace_id TEXT NOT NULL,
    mode TEXT NOT NULL CHECK (mode IN ('READ', 'DEVELOP')),
    PRIMARY KEY (tenant_id, principal_type, principal_id, workspace_id)
);
