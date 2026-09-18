CREATE INDEX idx_coding_follow_up_dispatched_run
    ON coding_follow_up(dispatched_run_id)
    WHERE dispatched_run_id IS NOT NULL;
