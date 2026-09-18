#!/usr/bin/env python3

import csv
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path


CASE_ROOT = Path(__file__).resolve().parent


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command, cwd, timeout=60):
    return subprocess.run(
        command,
        cwd=cwd,
        capture_output=True,
        text=True,
        timeout=timeout,
    )


def main() -> int:
    workspace = Path(sys.argv[1]).resolve()
    checks = {}
    failures = []

    baseline_tests = CASE_ROOT / "base-workspace" / "tests"
    candidate_tests = workspace / "tests"
    expected = {
        path.relative_to(baseline_tests): digest(path)
        for path in baseline_tests.rglob("*")
        if path.is_file()
    }
    actual = {
        path.relative_to(candidate_tests): digest(path)
        for path in candidate_tests.rglob("*")
        if path.is_file() and "__pycache__" not in path.parts
    }
    checks["testsUnchanged"] = expected == actual
    if not checks["testsUnchanged"]:
        failures.append("existing tests were modified")

    visible = run(
        [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"],
        workspace,
    )
    checks["visibleTests"] = visible.returncode == 0
    if visible.returncode != 0:
        failures.append("visible unittest suite failed")

    diff = run(["git", "diff", "--check"], workspace)
    checks["diffCheck"] = diff.returncode == 0
    if diff.returncode != 0:
        failures.append("git diff --check failed")

    with tempfile.TemporaryDirectory() as temporary:
        fixture = Path(temporary) / "edge.csv"
        with fixture.open("w", newline="", encoding="utf-8") as target:
            writer = csv.writer(target)
            writer.writerow(["id", "date", "category", "amount"])
            writer.writerows(
                [
                    ["a", "2026-02-28", "food", "90071992547409.91"],
                    ["b", "2024-02-29", "food", "-0.01"],
                    ["a", "2026-03-01", "ignored", "500.00"],
                    ["c", "2025-02-29", "invalid", "3.00"],
                    ["d", "2026-03-02", "travel", "1.001"],
                    ["e", "2026-03-03", "travel", "Infinity"],
                    ["f", "2026-03-04", "travel", "0.09"],
                ]
            )
        environment = os.environ.copy()
        environment["PYTHONPATH"] = str(workspace)
        script = (
            "import json,sys; "
            "from expense_report import analyze_csv; "
            "print(json.dumps(analyze_csv(sys.argv[1]), ensure_ascii=False))"
        )
        api = subprocess.run(
            [sys.executable, "-c", script, str(fixture)],
            cwd=workspace,
            env=environment,
            capture_output=True,
            text=True,
            timeout=30,
        )
        expected_report = {
            "transaction_count": 3,
            "invalid_row_count": 3,
            "total": "90071992547409.99",
            "by_category": {
                "food": "90071992547409.90",
                "travel": "0.09",
            },
        }
        try:
            api_report = json.loads(api.stdout)
        except json.JSONDecodeError:
            api_report = None
        checks["hiddenApi"] = api.returncode == 0 and api_report == expected_report
        if not checks["hiddenApi"]:
            failures.append("hidden precision/validation/API scenario failed")

        cli = run(
            [sys.executable, "-m", "expense_report", str(fixture)],
            workspace,
        )
        try:
            cli_report = json.loads(cli.stdout)
        except json.JSONDecodeError:
            cli_report = None
        checks["hiddenCli"] = cli.returncode == 0 and cli_report == expected_report
        if not checks["hiddenCli"]:
            failures.append("hidden CLI JSON scenario failed")

    payload = {
        "case": "01-python-debug",
        "passed": not failures,
        "checks": checks,
        "failures": failures,
    }
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
