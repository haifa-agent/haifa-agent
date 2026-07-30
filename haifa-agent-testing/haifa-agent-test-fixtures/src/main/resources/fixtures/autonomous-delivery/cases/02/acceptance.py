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


def run(command, cwd, timeout=90):
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

    checks["testsUnchanged"] = (
        digest(CASE_ROOT / "base-workspace" / "test.sh")
        == digest(workspace / "test.sh")
    )
    if not checks["testsUnchanged"]:
        failures.append("test.sh was modified")

    regression = run(["./test.sh"], workspace)
    checks["visibleTests"] = regression.returncode == 0
    if regression.returncode != 0:
        failures.append("repository regression failed")

    diff = run(["git", "diff", "--check"], workspace)
    checks["diffCheck"] = diff.returncode == 0
    if diff.returncode != 0:
        failures.append("git diff --check failed")

    with tempfile.TemporaryDirectory() as temporary:
        temporary_path = Path(temporary)
        classes = temporary_path / "classes"
        classes.mkdir()
        sources = sorted(
            str(path)
            for path in (workspace / "src" / "main" / "java").rglob("*.java")
        )
        compiled = run(
            ["javac", "--release", "21", "-d", str(classes), *sources],
            workspace,
        )
        checks["compiles"] = compiled.returncode == 0
        if compiled.returncode != 0:
            failures.append("Java sources did not compile")
        else:
            database = temporary_path / "nested" / "board.tsv"

            def cli(*arguments):
                return run(
                    [
                        "java",
                        "-cp",
                        str(classes),
                        "io.haifa.board.Main",
                        str(database),
                        *arguments,
                    ],
                    workspace,
                )

            additions = [
                cli("add", 'Fix "quoted" path \\ now', "HIGH", "2026-08-02"),
                cli("add", "国际化任务", "low", "-"),
                cli("add", "Ship release", "medium", "2026-08-01"),
            ]
            checks["addAndPersist"] = (
                [item.returncode for item in additions] == [0, 0, 0]
                and [item.stdout.strip() for item in additions] == ["1", "2", "3"]
            )
            if not checks["addAndPersist"]:
                failures.append("add/persistence scenario failed")

            completed = cli("done", "3")
            filtered = cli("list", "--status", "open", "--priority", "high")
            checks["filters"] = (
                completed.returncode == 0
                and filtered.returncode == 0
                and filtered.stdout
                == '1\tOPEN\tHIGH\t2026-08-02\tFix "quoted" path \\ now\n'
            )
            if not checks["filters"]:
                failures.append("combined filter scenario failed")

            exported = cli("export", "--format", "json")
            try:
                data = json.loads(exported.stdout)
            except json.JSONDecodeError:
                data = None
            checks["jsonExport"] = (
                exported.returncode == 0
                and isinstance(data, list)
                and [row["id"] for row in data] == [1, 2, 3]
                and list(data[0].keys())
                == ["id", "title", "priority", "due", "status"]
                and data[0]["title"] == 'Fix "quoted" path \\ now'
                and data[1]["title"] == "国际化任务"
                and data[2]["status"] == "DONE"
            )
            if not checks["jsonExport"]:
                failures.append("hidden JSON export scenario failed")

            invalid_cases = [
                cli("add", "bad", "urgent", "-"),
                cli("add", "bad", "low", "2026-02-30"),
                cli("list", "--status", "waiting"),
                cli("done", "not-an-id"),
                cli("export", "--format", "yaml"),
            ]
            checks["invalidSyntax"] = all(
                item.returncode == 2
                and item.stderr.strip()
                and "Exception" not in item.stderr
                and "\tat " not in item.stderr
                for item in invalid_cases
            )
            if not checks["invalidSyntax"]:
                failures.append("invalid syntax/diagnostic scenario failed")

            missing = cli("done", "999")
            checks["operationalError"] = (
                missing.returncode == 1
                and missing.stderr.strip()
                and "Exception" not in missing.stderr
            )
            if not checks["operationalError"]:
                failures.append("unknown ID operational error contract failed")

    payload = {
        "case": "02-java-product",
        "passed": not failures,
        "checks": checks,
        "failures": failures,
    }
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
