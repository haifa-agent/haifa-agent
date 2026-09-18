#!/usr/bin/env python3

import hashlib
import json
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

    baseline_fixtures = CASE_ROOT / "base-workspace" / "fixtures"
    candidate_fixtures = workspace / "fixtures"
    expected_fixtures = {
        path.relative_to(baseline_fixtures): digest(path)
        for path in baseline_fixtures.rglob("*")
        if path.is_file()
    }
    actual_fixtures = {
        path.relative_to(candidate_fixtures): digest(path)
        for path in candidate_fixtures.rglob("*")
        if path.is_file()
    }
    checks["fixturesUnchanged"] = expected_fixtures == actual_fixtures
    if not checks["fixturesUnchanged"]:
        failures.append("fixtures were modified")

    checks["readmeDelivered"] = (
        (workspace / "README.md").is_file()
        and "audit.py" in (workspace / "README.md").read_text("utf-8")
    )
    if not checks["readmeDelivered"]:
        failures.append("README usage was not delivered")

    test_files = list((workspace / "tests").glob("test*.py"))
    checks["testsDelivered"] = bool(test_files)
    if not checks["testsDelivered"]:
        failures.append("automated tests were not delivered")

    tests = run(
        [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"],
        workspace,
    )
    checks["candidateTests"] = tests.returncode == 0
    if tests.returncode != 0:
        failures.append("candidate test suite failed")

    diff = run(["git", "diff", "--check"], workspace)
    checks["diffCheck"] = diff.returncode == 0
    if diff.returncode != 0:
        failures.append("git diff --check failed")

    fixture = workspace / "fixtures" / "events.jsonl"
    first = run([sys.executable, "audit.py", str(fixture)], workspace)
    second = run([sys.executable, "audit.py", str(fixture)], workspace)
    try:
        report = json.loads(first.stdout)
    except json.JSONDecodeError:
        report = None
    expected_report = {
        "schemaVersion": 1,
        "totalLines": 7,
        "validEvents": 5,
        "invalidLines": 2,
        "riskScore": 10,
        "byAction": {"DELETE": 1, "LOGIN": 2, "READ": 1, "WRITE": 1},
        "byOutcome": {"DENIED": 1, "FAILED": 1, "SUCCESS": 3},
        "topActors": [
            {"actor": "alice", "events": 3},
            {"actor": "bob", "events": 1},
            {"actor": "陈", "events": 1},
        ],
        "suspicious": [
            {
                "line": 1,
                "actor": "alice",
                "action": "LOGIN",
                "outcome": "FAILED",
                "risk": 5,
            },
            {
                "line": 4,
                "actor": "陈",
                "action": "READ",
                "outcome": "DENIED",
                "risk": 3,
            },
            {
                "line": 3,
                "actor": "bob",
                "action": "DELETE",
                "outcome": "SUCCESS",
                "risk": 2,
            },
        ],
    }
    checks["fixtureReport"] = first.returncode == 0 and report == expected_report
    if not checks["fixtureReport"]:
        failures.append("standard fixture report failed")

    checks["deterministic"] = (
        first.returncode == 0
        and second.returncode == 0
        and first.stdout.encode() == second.stdout.encode()
        and first.stderr == ""
        and second.stderr == ""
    )
    if not checks["deterministic"]:
        failures.append("stdout report was not deterministic/clean")

    with tempfile.TemporaryDirectory() as temporary:
        temporary_path = Path(temporary)
        edge = temporary_path / "edge.jsonl"
        edge.write_text(
            "\n".join(
                [
                    '{"timestamp":"2026-07-29T01:00:00+08:00","actor":"zoe","action":"READ","resource":"r","outcome":"DENIED","ip":"2001:db8::1"}',
                    '{"timestamp":"2026-07-29T01:01:00","actor":"zoe","action":"LOGIN","resource":"r","outcome":"FAILED","ip":"192.0.2.1"}',
                    '{"timestamp":"2026-07-29T01:02:00Z","actor":"","action":"WRITE","resource":"r","outcome":"SUCCESS","ip":"192.0.2.2"}',
                    '{"timestamp":"2026-07-29T01:03:00Z","actor":"x","action":"READ","resource":"r","outcome":"SUCCESS","ip":"999.1.1.1"}',
                    "",
                    "",
                ]
            ),
            encoding="utf-8",
        )
        output = temporary_path / "report.json"
        edge_run = run(
            [
                sys.executable,
                "audit.py",
                str(edge),
                "--out",
                str(output),
                "--min-risk",
                "3",
            ],
            workspace,
        )
        try:
            edge_report = json.loads(output.read_text("utf-8"))
        except (OSError, json.JSONDecodeError):
            edge_report = None
        checks["edgeReport"] = (
            edge_run.returncode == 0
            and edge_run.stdout == ""
            and edge_report
            == {
                "schemaVersion": 1,
                "totalLines": 5,
                "validEvents": 1,
                "invalidLines": 4,
                "riskScore": 3,
                "byAction": {"READ": 1},
                "byOutcome": {"DENIED": 1},
                "topActors": [{"actor": "zoe", "events": 1}],
                "suspicious": [
                    {
                        "line": 1,
                        "actor": "zoe",
                        "action": "READ",
                        "outcome": "DENIED",
                        "risk": 3,
                    }
                ],
            }
        )
        if not checks["edgeReport"]:
            failures.append("hidden malformed/offset/IP/output scenario failed")

    invalid_commands = [
        [sys.executable, "audit.py"],
        [sys.executable, "audit.py", str(fixture), "--min-risk", "-1"],
        [sys.executable, "audit.py", str(fixture), "--unknown"],
        [sys.executable, "audit.py", str(workspace / "missing.jsonl")],
    ]
    invalid_results = [run(command, workspace) for command in invalid_commands]
    checks["cliErrors"] = all(
        item.returncode == 2 and item.stderr.strip() and item.stdout == ""
        for item in invalid_results
    )
    if not checks["cliErrors"]:
        failures.append("CLI error contract failed")

    payload = {
        "case": "03-greenfield-audit",
        "passed": not failures,
        "checks": checks,
        "failures": failures,
    }
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
