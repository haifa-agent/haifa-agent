CREATE INDEX idx_run_parent_created
    ON run(parent_run_id, created_at);
