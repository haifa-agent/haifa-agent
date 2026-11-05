-- One rebuildable classification bit over existing source records, not another history store.
ALTER TABLE session_message ADD COLUMN history_requires_retention INTEGER NOT NULL DEFAULT 1;
UPDATE session_message SET history_requires_retention = CASE WHEN run_id IS NULL OR EXISTS (
    SELECT 1 FROM json_each(CAST(content_payload AS TEXT), '$.parts') part
    WHERE COALESCE(json_extract(part.value, '$.kind'), '') NOT IN ('text', 'tool-call-ref', 'tool-result-ref')
       OR (role = 'USER' AND json_extract(part.value, '$.kind') = 'text'
           AND (lower(json_extract(part.value, '$.text')) LIKE 'project constraint:%'
                OR json_extract(part.value, '$.text') LIKE '项目约束%'))
) THEN 1 ELSE 0 END;
CREATE INDEX idx_message_required_history ON session_message(session_id, sequence)
    WHERE history_requires_retention = 1 AND status = 'COMPLETED'
      AND visibility IN ('USER_VISIBLE', 'AGENT_VISIBLE');
