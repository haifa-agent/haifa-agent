#!/usr/bin/env python3
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    workspace = Path(sys.argv[1]).resolve()
    checks = {}
    checks["testsUnchanged"] = digest(ROOT / "base-workspace/test.js") == digest(workspace / "test.js")
    checks["visibleTests"] = subprocess.run(["node", "test.js"], cwd=workspace, capture_output=True, text=True).returncode == 0
    hidden = """
const assert=require('node:assert'); const {slug}=require('./slug');
assert.strictEqual(slug('  Hello,   WORLD!  '),'hello-world');
assert.strictEqual(slug('资料 / Résumé 2026'),'资料-résumé-2026');
for (const value of ['---','   ']) assert.throws(()=>slug(value),TypeError);
"""
    checks["unknownIntentDelivery"] = subprocess.run(["node", "-e", hidden], cwd=workspace, capture_output=True, text=True).returncode == 0
    checks["diffCheck"] = subprocess.run(["git", "diff", "--check"], cwd=workspace).returncode == 0
    failures = [name for name, passed in checks.items() if not passed]
    print(json.dumps({"passed": not failures, "checks": checks, "failures": failures}, ensure_ascii=False, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
