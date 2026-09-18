#!/usr/bin/env python3

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path


CASE_ROOT = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command, cwd, timeout=90, **kwargs):
    environment = os.environ.copy()
    environment["GOCACHE"] = str(cwd / ".acceptance-cache")
    environment["GOPATH"] = str(cwd / ".acceptance-go")
    return subprocess.run(
        command,
        cwd=cwd,
        env=environment,
        capture_output=True,
        text=True,
        timeout=timeout,
        **kwargs,
    )


def main():
    workspace = Path(sys.argv[1]).resolve()
    checks = {}
    failures = []

    checks["testsUnchanged"] = digest(
        CASE_ROOT / "base-workspace/dedupe/dedupe_test.go"
    ) == digest(workspace / "dedupe/dedupe_test.go")
    tests = run(["go", "test", "./..."], workspace)
    checks["visibleTests"] = tests.returncode == 0
    diff = run(["git", "diff", "--check"], workspace)
    checks["diffCheck"] = diff.returncode == 0

    with tempfile.TemporaryDirectory() as temporary:
        binary = Path(temporary) / "topdupes"
        built = run(["go", "build", "-o", str(binary), "./cmd/topdupes"], workspace)
        checks["build"] = built.returncode == 0
        if built.returncode == 0:
            values = []
            for index in range(220000):
                values.append(f" value-{index % 30000:05d} ")
            values.extend(["x" * 900000, "x" * 900000])
            payload = "\n".join(values) + "\n"
            started = time.monotonic()
            completed = subprocess.run(
                [str(binary), "-limit", "3"],
                input=payload,
                capture_output=True,
                text=True,
                timeout=8,
            )
            elapsed = time.monotonic() - started
            try:
                data = json.loads(completed.stdout)
            except json.JSONDecodeError:
                data = None
            checks["largeInputPerformance"] = (
                completed.returncode == 0 and elapsed < 3.0
            )
            checks["largeInputCorrectness"] = (
                isinstance(data, list)
                and len(data) == 3
                and data[0] == {"value": "value-00000", "count": 8}
                and data[1] == {"value": "value-00001", "count": 8}
                and data[2] == {"value": "value-00002", "count": 8}
            )

    for name, passed in checks.items():
        if not passed:
            failures.append(name)
    print(json.dumps({"case": "05-go-performance", "passed": not failures, "checks": checks, "failures": failures}, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
