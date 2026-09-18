-- Human interactions may remain pending until answered or explicitly cancelled.
-- Existing requests retain their configured deadline.

DROP INDEX idx_interaction_request_pending;
DROP INDEX idx_interaction_due;

ALTER TABLE interaction_request ADD COLUMN expires_at_v9 INTEGER
    CHECK (expires_at_v9 IS NULL OR expires_at_v9 > created_at);
UPDATE interaction_request SET expires_at_v9 = expires_at;
ALTER TABLE interaction_request DROP COLUMN expires_at;
ALTER TABLE interaction_request RENAME COLUMN expires_at_v9 TO expires_at;

CREATE INDEX idx_interaction_request_pending
ON interaction_request(run_id, expires_at, created_at);

CREATE INDEX idx_interaction_due
ON interaction_request(state, expires_at, request_id)
WHERE expires_at IS NOT NULL;
