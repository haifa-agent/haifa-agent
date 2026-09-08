CREATE TABLE coding_session_model_preference (
    session_id TEXT PRIMARY KEY NOT NULL,
    schema_version TEXT NOT NULL,
    model_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    idempotency_key_digest TEXT,
    request_digest TEXT,
    updated_at_ms INTEGER NOT NULL,
    FOREIGN KEY (session_id) REFERENCES project_product_session(session_id) ON DELETE CASCADE,
    CHECK (revision >= 0),
    CHECK ((idempotency_key_digest IS NULL) = (request_digest IS NULL))
);
