#!/usr/bin/env python3
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    workspace = Path(sys.argv[1]).resolve()
    checks = {}
    checks["testsUnchanged"] = digest(ROOT / "base-workspace/tests/test_cache_job.py") == digest(
        workspace / "tests/test_cache_job.py"
    )
    tests = subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"],
        cwd=workspace,
        capture_output=True,
        text=True,
        timeout=60,
    )
    checks["visibleTests"] = tests.returncode == 0
    with tempfile.TemporaryDirectory() as temporary:
        env = dict(os.environ)
        env["TMPDIR"] = temporary
        completed = subprocess.run(
            [sys.executable, "cache_job.py", "hidden-value"],
            cwd=workspace,
            env=env,
            capture_output=True,
            text=True,
            timeout=30,
        )
        checks["isolatedTempContract"] = (
            completed.returncode == 0
            and completed.stdout.strip() == "hidden-value"
            and (Path(temporary) / "haifa-python-cache" / "entry.txt").read_text() == "hidden-value"
        )
    checks["diffCheck"] = subprocess.run(
        ["git", "diff", "--check"], cwd=workspace, capture_output=True, text=True
    ).returncode == 0
    failures = [name for name, passed in checks.items() if not passed]
    print(json.dumps({"passed": not failures, "checks": checks, "failures": failures}, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
