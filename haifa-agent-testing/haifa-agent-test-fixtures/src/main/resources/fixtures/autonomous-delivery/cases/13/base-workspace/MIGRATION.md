# Version 1 to version 2

- Version 1 has `jobs(id, name)`.
- Version 2 adds non-null `state` defaulting existing rows to `pending` and index `ix_jobs_state`.
- Run the schema and `PRAGMA user_version=2` in one transaction.
- `MIGRATION_FAIL_AFTER_SCHEMA=1` simulates interruption after schema work; the database must remain byte-for-byte
  logically version 1 with its rows intact.
- Running migration on version 2 is a successful no-op.
- Reject unknown versions without mutation.
