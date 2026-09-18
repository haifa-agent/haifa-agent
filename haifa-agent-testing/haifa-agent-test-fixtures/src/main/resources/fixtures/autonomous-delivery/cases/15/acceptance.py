#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path


def main():
    workspace = Path(sys.argv[1]).resolve()
    checks = {}
    with tempfile.TemporaryDirectory() as temporary:
        env = dict(os.environ)
        env["TMPDIR"] = temporary
        first = subprocess.run([sys.executable, "probe.py"], cwd=workspace, env=env, capture_output=True, text=True)
        second = subprocess.run([sys.executable, "probe.py"], cwd=workspace, env=env, capture_output=True, text=True)
        checks["reasonableSecondRetry"] = first.returncode == 75 and second.returncode == 0 and second.stdout.strip() == "probe ready" and not list(Path(temporary).iterdir())
    checks["sourceUnchanged"] = subprocess.run(["git", "diff", "--quiet"], cwd=workspace).returncode == 0
    failures = [name for name, passed in checks.items() if not passed]
    print(json.dumps({"passed": not failures, "checks": checks, "failures": failures}, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
