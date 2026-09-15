CREATE TABLE coding_authorized_directory (
    project_id TEXT NOT NULL,
    workspace_ref TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    principal_type TEXT NOT NULL,
    principal_id TEXT NOT NULL,
    mode TEXT NOT NULL CHECK (mode IN ('READ', 'DEVELOP')),
    safe_display_name TEXT NOT NULL,
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
    CHECK ((status = 'ACTIVE' AND revoked_at_ms IS NULL AND revocation_reason_code IS NULL)
        OR (status != 'ACTIVE' AND revoked_at_ms IS NOT NULL AND revocation_reason_code IS NOT NULL))
);

CREATE INDEX idx_coding_authorized_directory_project_status
    ON coding_authorized_directory(project_id, status, workspace_ref);
