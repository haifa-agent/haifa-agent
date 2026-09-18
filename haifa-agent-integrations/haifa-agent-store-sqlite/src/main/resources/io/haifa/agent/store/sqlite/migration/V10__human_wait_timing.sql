ALTER TABLE run
ADD COLUMN accumulated_human_wait_millis INTEGER NOT NULL DEFAULT 0
CHECK (accumulated_human_wait_millis >= 0);

ALTER TABLE run
ADD COLUMN human_wait_started_at INTEGER
CHECK (human_wait_started_at IS NULL OR human_wait_started_at >= created_at);

-- Older schemas did not record when a human wait began. Start accounting from
-- migration time without inventing an unverified historical paused duration.
UPDATE run
SET human_wait_started_at = updated_at
WHERE status IN ('WAITING_INTERACTION', 'WAITING_APPROVAL');
