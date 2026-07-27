CREATE TABLE project_product_session (
    session_id TEXT PRIMARY KEY,
    schema_version TEXT NOT NULL,
    project_id TEXT NOT NULL,
    workspace_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    principal_id TEXT NOT NULL,
    principal_type TEXT NOT NULL,
    configuration_id TEXT NOT NULL,
    configuration_version TEXT NOT NULL,
    configuration_digest TEXT NOT NULL,
    product_profile_ref TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES session(session_id) ON DELETE CASCADE
);

CREATE INDEX idx_project_product_session_project
    ON project_product_session(project_id, session_id);
