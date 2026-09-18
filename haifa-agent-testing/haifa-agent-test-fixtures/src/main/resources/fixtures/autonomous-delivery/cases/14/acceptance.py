#!/usr/bin/env python3
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def tree(root):
    digest = hashlib.sha256()
    for path in sorted(
        p
        for p in root.rglob("*")
        if p.is_file()
        and ".git" not in p.parts
        and "__pycache__" not in p.parts
        and p.suffix != ".pyc"
    ):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def main():
    workspace = Path(sys.argv[1]).resolve()
    checks = {}
    checks["workspaceUnchanged"] = tree(ROOT / "base-workspace") == tree(workspace)
    reproduced = subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"],
        cwd=workspace,
        capture_output=True,
        text=True,
        timeout=30,
    )
    checks["failureReproducible"] = reproduced.returncode != 0 and "test_does_not_retry_client_errors" in reproduced.stderr
    checks["diffEmpty"] = subprocess.run(
        ["git", "diff", "--quiet"], cwd=workspace, timeout=30
    ).returncode == 0
    failures = [name for name, passed in checks.items() if not passed]
    print(json.dumps({"passed": not failures, "checks": checks, "failures": failures}, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
