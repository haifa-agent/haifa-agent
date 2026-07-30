#!/usr/bin/env python3
import hashlib
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def create_v1(path):
    connection = sqlite3.connect(path)
    connection.executescript(
        "CREATE TABLE jobs(id INTEGER PRIMARY KEY, name TEXT NOT NULL);"
        "INSERT INTO jobs VALUES(1,'one'),(2,'two'); PRAGMA user_version=1;"
    )
    connection.close()


def main():
    workspace = Path(sys.argv[1]).resolve()
    checks = {}
    checks["testsUnchanged"] = digest(ROOT / "base-workspace/tests/test_migrate.py") == digest(
        workspace / "tests/test_migrate.py"
    )
    checks["visibleTests"] = subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"],
        cwd=workspace,
        capture_output=True,
        text=True,
        timeout=60,
    ).returncode == 0
    with tempfile.TemporaryDirectory() as temporary:
        path = Path(temporary) / "jobs.db"
        create_v1(path)
        env = dict(os.environ)
        env["MIGRATION_FAIL_AFTER_SCHEMA"] = "1"
        failed = subprocess.run([sys.executable, "migrate.py", str(path)], cwd=workspace, env=env)
        connection = sqlite3.connect(path)
        version = connection.execute("PRAGMA user_version").fetchone()[0]
        columns = [row[1] for row in connection.execute("PRAGMA table_info(jobs)")]
        rows = connection.execute("SELECT * FROM jobs ORDER BY id").fetchall()
        connection.close()
        checks["failureAtomicity"] = failed.returncode != 0 and version == 1 and columns == ["id", "name"] and rows == [(1, "one"), (2, "two")]
        first = subprocess.run([sys.executable, "migrate.py", str(path)], cwd=workspace)
        second = subprocess.run([sys.executable, "migrate.py", str(path)], cwd=workspace)
        connection = sqlite3.connect(path)
        indexes = {row[1] for row in connection.execute("PRAGMA index_list(jobs)")}
        final = connection.execute("SELECT id,name,state FROM jobs ORDER BY id").fetchall()
        final_version = connection.execute("PRAGMA user_version").fetchone()[0]
        connection.close()
        checks["idempotentCompatibility"] = first.returncode == 0 and second.returncode == 0 and final_version == 2 and "ix_jobs_state" in indexes and final == [(1, "one", "pending"), (2, "two", "pending")]
    checks["diffCheck"] = subprocess.run(["git", "diff", "--check"], cwd=workspace).returncode == 0
    failures = [name for name, passed in checks.items() if not passed]
    print(json.dumps({"passed": not failures, "checks": checks, "failures": failures}, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
