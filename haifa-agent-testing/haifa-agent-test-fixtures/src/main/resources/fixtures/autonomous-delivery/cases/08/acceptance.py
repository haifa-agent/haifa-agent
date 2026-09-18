#!/usr/bin/env python3

import hashlib
import json
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path


CASE_ROOT = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def create_v1(path):
    connection = sqlite3.connect(path)
    connection.executescript(
        """
        CREATE TABLE notes (
          id INTEGER PRIMARY KEY,
          body TEXT NOT NULL,
          created_at TEXT NOT NULL
        );
        INSERT INTO notes VALUES
          (7, 'first', '2026-01-01T00:00:00Z'),
          (12, '第二条', '2026-02-02T03:04:05Z');
        PRAGMA user_version = 1;
        """
    )
    connection.close()


def run(command, cwd):
    return subprocess.run(
        command, cwd=cwd, capture_output=True, text=True, timeout=60
    )


def main():
    workspace = Path(sys.argv[1]).resolve()
    checks = {}
    failures = []
    checks["testsUnchanged"] = digest(
        CASE_ROOT / "base-workspace/tests/test_schema.py"
    ) == digest(workspace / "tests/test_schema.py")
    checks["visibleTests"] = run(
        [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"],
        workspace,
    ).returncode == 0
    checks["diffCheck"] = run(["git", "diff", "--check"], workspace).returncode == 0

    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        database = root / "v1.db"
        create_v1(database)
        first = run([sys.executable, "manage.py", "migrate", str(database)], workspace)
        second = run([sys.executable, "manage.py", "migrate", str(database)], workspace)
        connection = sqlite3.connect(database)
        connection.row_factory = sqlite3.Row
        version = connection.execute("PRAGMA user_version").fetchone()[0]
        columns = [
            row["name"] for row in connection.execute("PRAGMA table_info(notes)")
        ]
        rows = [
            dict(row)
            for row in connection.execute(
                "SELECT id, body, created_at, status, updated_at FROM notes ORDER BY id"
            )
        ]
        indexes = {
            row["name"] for row in connection.execute("PRAGMA index_list(notes)")
        }
        connection.close()
        checks["v1Migration"] = (
            first.returncode == 0
            and second.returncode == 0
            and first.stdout.strip() == "schema version 2"
            and version == 2
            and columns == ["id", "body", "created_at", "status", "updated_at"]
            and rows
            == [
                {
                    "id": 7,
                    "body": "first",
                    "created_at": "2026-01-01T00:00:00Z",
                    "status": "active",
                    "updated_at": "2026-01-01T00:00:00Z",
                },
                {
                    "id": 12,
                    "body": "第二条",
                    "created_at": "2026-02-02T03:04:05Z",
                    "status": "active",
                    "updated_at": "2026-02-02T03:04:05Z",
                },
            ]
            and "ix_notes_status_updated" in indexes
        )

        unsupported = root / "unsupported.db"
        connection = sqlite3.connect(unsupported)
        connection.execute("CREATE TABLE keep_me(value TEXT)")
        connection.execute("INSERT INTO keep_me VALUES ('safe')")
        connection.execute("PRAGMA user_version = 9")
        connection.commit()
        connection.close()
        rejected = run(
            [sys.executable, "manage.py", "migrate", str(unsupported)], workspace
        )
        connection = sqlite3.connect(unsupported)
        kept = connection.execute("SELECT value FROM keep_me").fetchone()
        kept_version = connection.execute("PRAGMA user_version").fetchone()[0]
        connection.close()
        checks["unsupportedRejected"] = (
            rejected.returncode != 0 and kept == ("safe",) and kept_version == 9
        )

        unrelated = root / "unrelated.db"
        connection = sqlite3.connect(unrelated)
        connection.execute("CREATE TABLE business_data(id INTEGER)")
        connection.execute("INSERT INTO business_data VALUES (42)")
        connection.commit()
        connection.close()
        rejected_zero = run(
            [sys.executable, "manage.py", "migrate", str(unrelated)], workspace
        )
        connection = sqlite3.connect(unrelated)
        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }
        value = connection.execute("SELECT id FROM business_data").fetchone()
        connection.close()
        checks["unrelatedV0Rejected"] = (
            rejected_zero.returncode != 0
            and tables == {"business_data"}
            and value == (42,)
        )

    for name, passed in checks.items():
        if not passed:
            failures.append(name)
    print(json.dumps({"case": "08-sqlite-migration", "passed": not failures, "checks": checks, "failures": failures}, ensure_ascii=False, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
