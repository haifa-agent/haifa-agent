# Notes database v2

Existing v1 databases use:

```sql
CREATE TABLE notes (
  id INTEGER PRIMARY KEY,
  body TEXT NOT NULL,
  created_at TEXT NOT NULL
);
PRAGMA user_version = 1;
```

Version 2 keeps all columns and adds:

- `status TEXT NOT NULL DEFAULT 'active'` constrained to `active|archived`;
- `updated_at TEXT NOT NULL`, backfilled from each row's `created_at`;
- index `ix_notes_status_updated` on `(status, updated_at DESC)`;
- `PRAGMA user_version = 2`.

`notesdb.open_database(path)` must return a connection ready at v2.
`python3 manage.py migrate DATABASE` performs the same operation and prints
`schema version 2`.

Required guarantees:

- an empty/new database is created directly at v2;
- a valid v1 database upgrades without changing IDs, bodies or timestamps;
- existing rows become `active`, with `updated_at == created_at`;
- repeated opens/migrations are idempotent;
- migration is transactional: version changes only after all schema and data
  changes succeed;
- version 0 with unrelated user tables, unsupported versions, and malformed or
  partial v1 schemas fail without destructive repair;
- connections enable foreign keys and use sqlite3 row access.
