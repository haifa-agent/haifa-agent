#!/usr/bin/env python3
import hashlib
import json
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
    checks["testsUnchanged"] = digest(ROOT / "base-workspace/test.js") == digest(workspace / "test.js")
    checks["visibleTests"] = subprocess.run(
        ["node", "test.js"], cwd=workspace, capture_output=True, text=True, timeout=30
    ).returncode == 0
    with tempfile.TemporaryDirectory() as temporary:
        script = """
const fs=require('node:fs'); const path=require('node:path');
const {writeExport}=require('./exporter'); const root=process.argv[1];
for (const key of ['../escape.txt','safe/../../escape.txt','/absolute.txt','C:\\\\bad.txt','a\\\\b','a//b','.']) {
  const before=fs.existsSync(root) ? JSON.stringify(fs.readdirSync(root,{recursive:true}).sort()) : '[]';
  let rejected=false; try { writeExport(root,key,'owned'); } catch (_) { rejected=true; }
  const after=fs.existsSync(root) ? JSON.stringify(fs.readdirSync(root,{recursive:true}).sort()) : '[]';
  if (!rejected || before !== after) process.exit(3);
}
"""
        completed = subprocess.run(
            ["node", "-e", script, str(Path(temporary) / "destination")],
            cwd=workspace,
            capture_output=True,
            text=True,
            timeout=30,
        )
        checks["rejectionAtomicity"] = completed.returncode == 0
    checks["diffCheck"] = subprocess.run(
        ["git", "diff", "--check"], cwd=workspace, capture_output=True, text=True
    ).returncode == 0
    failures = [name for name, passed in checks.items() if not passed]
    print(json.dumps({"passed": not failures, "checks": checks, "failures": failures}, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
