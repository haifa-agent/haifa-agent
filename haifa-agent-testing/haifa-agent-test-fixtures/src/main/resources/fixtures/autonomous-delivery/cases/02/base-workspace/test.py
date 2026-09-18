import json
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent
SCRATCH = ROOT / "build" / "regression"
CLASSES = SCRATCH / "classes"
DATABASE = SCRATCH / "tasks.tsv"


def run(arguments):
    return subprocess.run(
        arguments,
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=False,
    )


def require(result, output=None):
    if result.returncode != 0:
        raise AssertionError(result.stderr)
    if output is not None and result.stdout.strip() != output:
        raise AssertionError(result.stdout)


def main():
    shutil.rmtree(SCRATCH, ignore_errors=True)
    CLASSES.mkdir(parents=True)
    sources = sorted(str(path) for path in (ROOT / "src/main/java").rglob("*.java"))
    require(run(["javac", "--release", "21", "-d", str(CLASSES), *sources]))

    def cli(*arguments):
        return run(
            [
                "java",
                "-cp",
                str(CLASSES),
                "io.haifa.board.Main",
                str(DATABASE),
                *arguments,
            ]
        )

    require(cli("add", "Write docs", "high", "2026-08-01"), "1")
    require(cli("add", "Release build", "medium", "-"), "2")
    require(cli("add", "Fix blocker", "HIGH", "2026-07-30"), "3")
    require(
        cli("list"),
        "1\tOPEN\tHIGH\t2026-08-01\tWrite docs\n"
        "2\tOPEN\tMEDIUM\t-\tRelease build\n"
        "3\tOPEN\tHIGH\t2026-07-30\tFix blocker",
    )
    require(cli("done", "2"), "done 2")
    require(
        cli("list", "--priority", "high", "--status", "open"),
        "1\tOPEN\tHIGH\t2026-08-01\tWrite docs\n"
        "3\tOPEN\tHIGH\t2026-07-30\tFix blocker",
    )
    exported = cli("export", "--format", "json")
    require(exported)
    data = json.loads(exported.stdout)
    assert [row["id"] for row in data] == [1, 2, 3]
    assert data[1]["status"] == "DONE"
    assert cli("list", "--status", "missing").returncode != 0
    shutil.rmtree(SCRATCH, ignore_errors=True)
    print("Task Board regression: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
