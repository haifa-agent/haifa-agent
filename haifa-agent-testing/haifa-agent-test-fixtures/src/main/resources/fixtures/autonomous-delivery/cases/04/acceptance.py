#!/usr/bin/env python3

import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path


CASE_ROOT = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command, cwd, **kwargs):
    return subprocess.run(
        command, cwd=cwd, capture_output=True, text=True, timeout=60, **kwargs
    )


def main():
    workspace = Path(sys.argv[1]).resolve()
    checks = {}
    failures = []

    checks["testsUnchanged"] = digest(
        CASE_ROOT / "base-workspace/tests/test_catalog.py"
    ) == digest(workspace / "tests/test_catalog.py")
    tests = run(
        [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"],
        workspace,
    )
    checks["visibleTests"] = tests.returncode == 0
    diff = run(["git", "diff", "--check"], workspace)
    checks["diffCheck"] = diff.returncode == 0

    script = r'''
import copy, json
from dataclasses import FrozenInstanceError
from catalog import SearchRequest, SearchResult, search, search_v2
from consumers.report import matching_titles

items = [
 {"id":1,"title":"Straße Map","description":"","tags":["Travel","EU"]},
 {"id":2,"title":"Guide","description":"STRASSE routes","tags":["EU","Travel","PDF"]},
 {"id":3,"title":"Other","description":"none","tags":["Travel"]},
]
before = copy.deepcopy(items)
request = SearchRequest(query="strasse", required_tags=("travel","eu"), offset=0, limit=1)
result = search_v2(items, request)
assert isinstance(result, SearchResult)
assert result.total == 2 and result.items == (items[0],)
assert items == before and result.items[0] is items[0]
assert search(items, "strasse") == items[:2]
assert matching_titles(items, "strasse") == ["Straße Map", "Guide"]
for create_bad in [
 lambda: SearchRequest(offset=-1),
 lambda: SearchRequest(limit=0),
 lambda: SearchRequest(limit=101),
]:
 try: search_v2(items, create_bad())
 except ValueError: pass
 else: raise AssertionError("invalid page accepted")
try: search_v2(items, {"query":""})
except ValueError: pass
else: raise AssertionError("invalid request accepted")
try:
 request.limit = 2
except (FrozenInstanceError, AttributeError):
 pass
else:
 raise AssertionError("request is mutable")
print(json.dumps({"ok": True}))
'''
    environment = os.environ.copy()
    environment["PYTHONPATH"] = str(workspace)
    hidden = subprocess.run(
        [sys.executable, "-c", script],
        cwd=workspace,
        env=environment,
        capture_output=True,
        text=True,
        timeout=30,
    )
    checks["hiddenContract"] = hidden.returncode == 0

    cli_input = json.dumps(
        [{"id": 1, "title": "Alpha", "description": "", "tags": []}]
    )
    cli = subprocess.run(
        [sys.executable, "-m", "consumers.cli", "alpha"],
        cwd=workspace,
        input=cli_input,
        capture_output=True,
        text=True,
        timeout=30,
    )
    try:
        cli_data = json.loads(cli.stdout)
    except json.JSONDecodeError:
        cli_data = None
    checks["consumerCli"] = cli.returncode == 0 and cli_data == [
        {"id": 1, "title": "Alpha", "description": "", "tags": []}
    ]

    for name, passed in checks.items():
        if not passed:
            failures.append(name)
    print(json.dumps({"case": "04-python-api-migration", "passed": not failures, "checks": checks, "failures": failures}, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
