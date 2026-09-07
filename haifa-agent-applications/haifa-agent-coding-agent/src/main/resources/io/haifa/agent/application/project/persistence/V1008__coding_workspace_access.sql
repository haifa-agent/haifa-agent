CREATE TABLE coding_workspace_access (
    tenant_id TEXT NOT NULL,
    principal_type TEXT NOT NULL,
    principal_id TEXT NOT NULL,
    workspace_id TEXT NOT NULL,
    mode TEXT NOT NULL CHECK (mode IN ('READ', 'DEVELOP')),
    PRIMARY KEY (tenant_id, principal_type, principal_id, workspace_id)
);
