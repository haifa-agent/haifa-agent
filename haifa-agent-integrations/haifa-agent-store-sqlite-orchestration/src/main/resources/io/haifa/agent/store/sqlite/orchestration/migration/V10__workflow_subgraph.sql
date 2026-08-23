-- Stable parent/child relation for restricted static subgraph workflow runs.

CREATE TABLE workflow_subgraph_instance (
    child_workflow_run_id TEXT PRIMARY KEY REFERENCES workflow_run(workflow_run_id),
    parent_workflow_run_id TEXT NOT NULL REFERENCES workflow_run(workflow_run_id),
    parent_node_id TEXT NOT NULL,
    parent_node_attempt INTEGER NOT NULL CHECK (parent_node_attempt > 0),
    active INTEGER NOT NULL CHECK (active IN (0,1)),
    UNIQUE (parent_workflow_run_id, parent_node_id, parent_node_attempt),
    CHECK (child_workflow_run_id <> parent_workflow_run_id)
) STRICT;

CREATE INDEX idx_workflow_subgraph_parent
    ON workflow_subgraph_instance(parent_workflow_run_id, active, parent_node_id, parent_node_attempt);
