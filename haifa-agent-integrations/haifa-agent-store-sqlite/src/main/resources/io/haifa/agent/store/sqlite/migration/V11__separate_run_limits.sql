ALTER TABLE run
ADD COLUMN limit_max_tool_calls INTEGER NOT NULL DEFAULT 0
CHECK (limit_max_tool_calls >= 0);

ALTER TABLE run
ADD COLUMN limit_max_model_calls INTEGER NOT NULL DEFAULT 1
CHECK (limit_max_model_calls > 0);

ALTER TABLE run
ADD COLUMN limit_max_child_runs INTEGER NOT NULL DEFAULT 0
CHECK (limit_max_child_runs >= 0);

-- Before this migration, the three budget columns also stored the corresponding
-- run limits. Preserve those limits and recover the real frozen budget values
-- from the content-addressed configuration snapshot.
UPDATE run
SET limit_max_tool_calls = budget_max_tool_calls,
    limit_max_model_calls = budget_max_model_calls,
    limit_max_child_runs = budget_max_child_runs,
    budget_max_tool_calls = COALESCE(
        (SELECT CAST(json_extract(CAST(content_payload AS TEXT), '$.budget.maxToolCalls') AS INTEGER)
         FROM configuration_snapshot
         WHERE configuration_ref = run.configuration_ref),
        budget_max_tool_calls),
    budget_max_model_calls = COALESCE(
        (SELECT CAST(json_extract(CAST(content_payload AS TEXT), '$.budget.maxModelCalls') AS INTEGER)
         FROM configuration_snapshot
         WHERE configuration_ref = run.configuration_ref),
        budget_max_model_calls),
    budget_max_child_runs = COALESCE(
        (SELECT CAST(json_extract(CAST(content_payload AS TEXT), '$.budget.maxChildRuns') AS INTEGER)
         FROM configuration_snapshot
         WHERE configuration_ref = run.configuration_ref),
        budget_max_child_runs);
