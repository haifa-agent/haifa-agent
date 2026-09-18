import os
import sqlite3
import sys


def migrate(path: str) -> None:
    connection = sqlite3.connect(path)
    version = connection.execute("PRAGMA user_version").fetchone()[0]
    if version == 2:
        connection.close()
        return
    if version != 1:
        connection.close()
        raise ValueError("unsupported schema")
    connection.execute("ALTER TABLE jobs ADD COLUMN state TEXT NOT NULL DEFAULT 'pending'")
    connection.commit()
    if os.environ.get("MIGRATION_FAIL_AFTER_SCHEMA") == "1":
        connection.close()
        raise RuntimeError("simulated interruption")
    connection.execute("CREATE INDEX ix_jobs_state ON jobs(state)")
    connection.execute("PRAGMA user_version = 2")
    connection.commit()
    connection.close()


if __name__ == "__main__":
    try:
        migrate(sys.argv[1])
    except Exception as error:
        print(f"migration: {error}", file=sys.stderr)
        raise SystemExit(1)
