ALTER TABLE coding_session_command
    ADD COLUMN delivery_intent TEXT NOT NULL DEFAULT 'WORKTREE_ONLY'
    CHECK (delivery_intent IN ('WORKTREE_ONLY', 'LOCAL_COMMIT', 'REMOTE_PUSH', 'PULL_REQUEST'));

CREATE INDEX idx_coding_session_command_run
    ON coding_session_command(run_id)
    WHERE run_id IS NOT NULL;

CREATE INDEX idx_coding_session_command_pending
    ON coding_session_command(session_id, created_at_ms DESC)
    WHERE run_id IS NULL;
