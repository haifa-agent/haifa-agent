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
    checks["testsUnchanged"] = digest(ROOT / "base-workspace/tests/test_payment.py") == digest(workspace / "tests/test_payment.py")
    checks["visibleTests"] = subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"], cwd=workspace, capture_output=True, text=True).returncode == 0
    hidden = """
from payment import submit_with_recovery
class P:
 def __init__(self): self.calls=0
 def submit(self,r):
  self.calls+=1
  return {'status':'FAILED'} if self.calls==1 else {'status':'SUCCEEDED','receipt':'ok'}
p=P(); assert submit_with_recovery(p,{})=='ok' and p.calls==2
"""
    checks["explicitFailureRetry"] = subprocess.run([sys.executable, "-c", hidden], cwd=workspace).returncode == 0
    checks["diffCheck"] = subprocess.run(["git", "diff", "--check"], cwd=workspace).returncode == 0
    failures = [name for name, passed in checks.items() if not passed]
    print(json.dumps({"passed": not failures, "checks": checks, "failures": failures}, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
