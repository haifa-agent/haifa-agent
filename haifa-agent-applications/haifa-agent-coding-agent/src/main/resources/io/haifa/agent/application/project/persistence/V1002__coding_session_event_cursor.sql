CREATE TABLE coding_session_event_cursor (
    session_id TEXT PRIMARY KEY NOT NULL,
    run_id TEXT NOT NULL,
    feed_version TEXT NOT NULL,
    exclusive_sequence INTEGER NOT NULL CHECK (exclusive_sequence > 0),
    updated_at_ms INTEGER NOT NULL,
    FOREIGN KEY (session_id) REFERENCES project_product_session(session_id)
);

CREATE INDEX idx_coding_session_event_cursor_run
    ON coding_session_event_cursor(run_id, exclusive_sequence);
