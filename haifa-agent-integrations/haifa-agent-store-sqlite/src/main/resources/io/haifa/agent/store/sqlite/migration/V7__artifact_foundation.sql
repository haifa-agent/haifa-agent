CREATE TABLE artifact_payload (
    payload_id TEXT PRIMARY KEY NOT NULL,
    sha256 TEXT NOT NULL UNIQUE CHECK (
        substr(sha256, 1, 7) = 'sha256:'
        AND length(sha256) = 71
        AND substr(sha256, 8) NOT GLOB '*[^0-9a-f]*'
    ),
    byte_count INTEGER NOT NULL CHECK (byte_count >= 0),
    payload BLOB NOT NULL,
    reference_count INTEGER NOT NULL CHECK (reference_count > 0),
    created_at INTEGER NOT NULL,
    CHECK (length(payload) = byte_count)
) STRICT;

CREATE TABLE artifact_record (
    artifact_id TEXT NOT NULL,
    artifact_version INTEGER NOT NULL CHECK (artifact_version > 0),
    artifact_type TEXT NOT NULL,
    title TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PUBLISHED', 'REVOKED')),
    created_at INTEGER NOT NULL,
    payload_id TEXT NOT NULL,
    payload_sha256 TEXT NOT NULL,
    payload_byte_count INTEGER NOT NULL CHECK (payload_byte_count >= 0),
    payload_media_type TEXT NOT NULL,
    project_id TEXT NOT NULL,
    workspace_ref TEXT NOT NULL,
    run_id TEXT,
    session_id TEXT,
    file_change_set_ref TEXT,
    execution_ref TEXT,
    source_logical_path TEXT NOT NULL,
    source_hash TEXT NOT NULL,
    export_policy TEXT NOT NULL,
    created_principal_id TEXT NOT NULL,
    created_principal_type TEXT NOT NULL,
    PRIMARY KEY (artifact_id, artifact_version),
    FOREIGN KEY (payload_id) REFERENCES artifact_payload(payload_id)
) STRICT;

CREATE INDEX idx_artifact_record_project
    ON artifact_record(project_id, artifact_id, artifact_version);
