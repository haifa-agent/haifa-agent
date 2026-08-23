ALTER TABLE tool_journal ADD COLUMN dispatch_execution_id TEXT;
ALTER TABLE tool_journal ADD COLUMN dispatch_process_id INTEGER;
ALTER TABLE tool_journal ADD COLUMN dispatch_workdir_digest TEXT;
ALTER TABLE tool_journal ADD COLUMN reconcile_status TEXT;
ALTER TABLE tool_journal ADD COLUMN reconcile_reason TEXT;

CREATE INDEX idx_tool_journal_dispatch_execution
ON tool_journal(dispatch_execution_id)
WHERE dispatch_execution_id IS NOT NULL;
