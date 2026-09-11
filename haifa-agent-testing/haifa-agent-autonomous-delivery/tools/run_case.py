#!/usr/bin/env python3
"""Minimal single-case runner for the autonomous-delivery capability ladder.

Usage:
  run_case.py --assets-dir ASSETS --case L1-01 --mode nop|oracle|agent [--agent-command CMD] [--repeat N]
              [--report local-tmp/autonomous-delivery/ladder-report.json]

Modes:
  nop     copy base-workspace unchanged; acceptance must FAIL
  oracle  copy base-workspace, overlay reference/; acceptance must PASS
  agent   run --agent-command with {workspace} and {prompt_file} replaced by fresh case paths

Budget: the per-case `runnerBudget.timeoutSeconds` (or `--timeout-seconds`) bounds the agent
command. A run that exceeds the budget is reported as INCOMPLETE_BUDGET and is never counted as
a capability failure.

The runner only prepares a throwaway workdir, invokes the case acceptance script outside the
workspace, validates its result against the ladder result contract, and prints one compact line
(or JSON record) per run. Reports contain no host paths.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

MODULE_ROOT = Path(__file__).resolve().parents[1]
ASSET_MANIFEST_NAME = "assets-manifest.json"
MODES = ("nop", "oracle", "agent")
DEFAULT_AGENT_TIMEOUT_SECONDS = 3600
DEFAULT_ACCEPTANCE_TIMEOUT_SECONDS = 900
BUDGET_EXIT_CODE = 3

CASE_ID_PATTERN = re.compile(r"^caseId:\s*(L[1-6]-0[1-9])", re.MULTILINE)
LEVEL_PATTERN = re.compile(r"^level:\s*(L[1-6])", re.MULTILINE)
LABEL_PATTERN = re.compile(r"^\s+(localization|modificationSpan|acceptance):\s*(\d+)", re.MULTILINE)
VARIANTS_PATTERN = re.compile(r"^variants:\s*\[([^\]]*)\]", re.MULTILINE)
BUDGET_PATTERN = re.compile(r"timeoutSeconds:\s*(\d+)")
CASE_VERSION_PATTERN = re.compile(r"^caseVersion:\s*(\S+)", re.MULTILINE)


def case_tree_sha256(case_root: Path) -> str:
    """Return the deterministic content digest published by the asset repository."""
    digest = hashlib.sha256()
    for path in sorted(case_root.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(case_root).as_posix().encode("utf-8")
        digest.update(relative)
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def verify_assets_root(assets_root: Path) -> tuple[Path, dict]:
    """Validate an offline asset checkout and return its case root and manifest."""
    assets_root = assets_root.resolve()
    manifest_path = assets_root / ASSET_MANIFEST_NAME
    if not manifest_path.is_file():
        raise SystemExit(f"assets directory has no {ASSET_MANIFEST_NAME}: {assets_root}")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise SystemExit(f"invalid asset manifest: {error.msg}") from error
    if not isinstance(manifest, dict) or manifest.get("schemaVersion") != 1:
        raise SystemExit("asset manifest schemaVersion must be 1")
    if manifest.get("caseRoot") != "cases":
        raise SystemExit("asset manifest caseRoot must be cases")
    cases = manifest.get("cases")
    if not isinstance(cases, list) or not cases or not all(isinstance(case_id, str) for case_id in cases):
        raise SystemExit("asset manifest cases must be a non-empty string list")
    if len(cases) != len(set(cases)) or any(not re.fullmatch(r"L[1-6]-0[1-9]", case_id) for case_id in cases):
        raise SystemExit("asset manifest cases must contain unique ladder case ids")
    case_root = assets_root / manifest["caseRoot"]
    actual_cases = sorted(path.name for path in case_root.glob("L*-*") if path.is_dir())
    if actual_cases != sorted(cases):
        raise SystemExit("asset manifest case list does not match case directories")
    expected_digest = manifest.get("caseTreeSha256")
    if not isinstance(expected_digest, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_digest):
        raise SystemExit("asset manifest caseTreeSha256 must be a lowercase SHA-256 digest")
    if case_tree_sha256(case_root) != expected_digest:
        raise SystemExit("asset case content does not match assets-manifest.json")
    return case_root, manifest


def copy_tree(source: Path, destination: Path) -> None:
    shutil.copytree(source, destination, dirs_exist_ok=True)


def overlay_reference(case_dir: Path, workspace: Path) -> None:
    reference = case_dir / "reference"
    if not reference.is_dir():
        raise SystemExit(f"case {case_dir.name} has no reference/ directory")
    for path in sorted(reference.rglob("*")):
        if path.is_file():
            target = workspace / path.relative_to(reference)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)


def case_metadata(case_dir: Path) -> dict:
    """Read the case metadata the runner needs without a YAML dependency."""
    text = (case_dir / "case.yaml").read_text(encoding="utf-8")
    case_id = CASE_ID_PATTERN.search(text)
    level = LEVEL_PATTERN.search(text)
    budget = BUDGET_PATTERN.search(text)
    version = CASE_VERSION_PATTERN.search(text)
    variants = VARIANTS_PATTERN.search(text)
    return {
        "caseId": case_id.group(1) if case_id else case_dir.name,
        "level": level.group(1) if level else case_dir.name[:2],
        "labels": {name: int(value) for name, value in LABEL_PATTERN.findall(text)},
        "variants": [item.strip() for item in variants.group(1).split(",") if item.strip()] if variants else [],
        "timeoutSeconds": int(budget.group(1)) if budget else None,
        "caseVersion": version.group(1) if version else "0.0.0",
    }


def run_acceptance(case_dir: Path, workspace: Path, timeout_seconds: int) -> tuple[int, dict | None, str]:
    acceptance = case_dir / "acceptance.py"
    completed = subprocess.run(
        [sys.executable, str(acceptance), str(workspace)],
        capture_output=True,
        text=True,
        timeout=timeout_seconds,
    )
    result: dict | None = None
    for line in reversed(completed.stdout.splitlines()):
        if line.strip().startswith("{"):
            try:
                result = json.loads(line)
                break
            except json.JSONDecodeError:
                continue
    return completed.returncode, result, completed.stderr.strip()


def result_contract_problems(result: dict | None, case_id: str) -> list[str]:
    """Return the contract violations of one acceptance result (empty when valid)."""
    if not isinstance(result, dict):
        return ["acceptance produced no JSON object"]
    problems: list[str] = []
    if result.get("schemaVersion") != 1:
        problems.append("schemaVersion must be 1")
    if result.get("caseId") != case_id:
        problems.append("caseId must equal the case directory name")
    if not isinstance(result.get("caseVersion"), str) or not result.get("caseVersion"):
        problems.append("caseVersion must be a non-empty string")
    if result.get("status") not in {"PASSED", "FAILED", "INCOMPLETE_BUDGET"}:
        problems.append("status must be PASSED, FAILED or INCOMPLETE_BUDGET")
    if not isinstance(result.get("passed"), bool):
        problems.append("passed must be a boolean")
    checks = result.get("checks")
    if not isinstance(checks, dict) or not checks or not all(isinstance(value, bool) for value in checks.values()):
        problems.append("checks must be a non-empty boolean map")
    failures = result.get("failures")
    if not isinstance(failures, list) or not all(isinstance(item, str) for item in failures):
        problems.append("failures must be a list of strings")
    elif isinstance(result.get("passed"), bool) and result["passed"] != (not failures):
        problems.append("passed must equal 'failures is empty'")
    return problems


def run_once(case_dir: Path, args: argparse.Namespace, index: int) -> dict:
    work_root = Path(args.work_root).resolve() if args.work_root else Path(tempfile.mkdtemp(prefix="ladder-"))
    work_root.mkdir(parents=True, exist_ok=True)
    workspace = work_root / f"{case_dir.name}-{args.mode}-{index}"
    if workspace.exists():
        shutil.rmtree(workspace)
    copy_tree(case_dir / "base-workspace", workspace)

    metadata = case_metadata(case_dir)
    started = time.monotonic()
    budget_exhausted: str | None = None
    if args.mode == "oracle":
        overlay_reference(case_dir, workspace)
    elif args.mode == "agent":
        if not args.agent_command:
            raise SystemExit("--agent-command is required in agent mode")
        timeout = args.timeout_seconds or metadata["timeoutSeconds"] or DEFAULT_AGENT_TIMEOUT_SECONDS
        command = (
            args.agent_command.replace("{workspace}", str(workspace))
            .replace("{prompt_file}", str(case_dir / "prompt.txt"))
        )
        try:
            agent = subprocess.run(command, shell=True, cwd=workspace, capture_output=True, text=True, timeout=timeout)
            if agent.returncode != 0:
                print(f"agent command exited {agent.returncode}", file=sys.stderr)
        except subprocess.TimeoutExpired:
            budget_exhausted = f"agent command exceeded {timeout}s"

    contract_problems: list[str] = []
    if budget_exhausted is not None:
        exit_code = BUDGET_EXIT_CODE
        stderr = ""
        verdict = "INCOMPLETE_BUDGET"
        accepted = False
        result = {
            "schemaVersion": 1,
            "caseId": metadata["caseId"],
            "caseVersion": metadata["caseVersion"],
            "status": "INCOMPLETE_BUDGET",
            "passed": False,
            "checks": {"budget.agentCommand": False},
            "failures": [budget_exhausted],
        }
    else:
        exit_code, result, stderr = run_acceptance(case_dir, workspace, args.acceptance_timeout)
        expected_pass = args.mode != "nop"
        accepted = bool(result.get("passed")) if isinstance(result, dict) else exit_code == 0
        verdict = "OK" if accepted == expected_pass else "UNEXPECTED"
        contract_problems = result_contract_problems(result, case_dir.name)
        if contract_problems:
            verdict = "UNEXPECTED"

    record = {
        "caseId": case_dir.name,
        "level": metadata["level"],
        "mode": args.mode,
        "verdict": verdict,
        "acceptanceExitCode": exit_code,
        "accepted": accepted,
        "expected": "PASSED" if args.mode != "nop" else "FAILED",
        "durationMillis": int((time.monotonic() - started) * 1000),
        "checks": result.get("checks") if isinstance(result, dict) else None,
        "failures": result.get("failures") if isinstance(result, dict) else None,
        "contractProblems": contract_problems,
    }
    if stderr:
        record["acceptanceStderr"] = stderr.splitlines()[-1][:400]
    if not args.keep_workdir:
        shutil.rmtree(workspace, ignore_errors=True)
        if not args.work_root:
            shutil.rmtree(work_root, ignore_errors=True)
    return record


def build_report(records: list[dict], args: argparse.Namespace, cases_root: Path) -> dict:
    """Aggregate runs per case and per level; host paths are deliberately excluded."""
    cases: dict[str, dict] = {}
    for record in records:
        entry = cases.setdefault(
            record["caseId"],
            {
                "caseId": record["caseId"],
                "level": record["level"],
                "labels": {},
                "variants": [],
                "runs": 0,
                "passedRuns": 0,
                "verdicts": {},
                "contractProblems": [],
                "durationMillis": [],
            },
        )
        entry["runs"] += 1
        entry["passedRuns"] += 1 if record["accepted"] else 0
        entry["verdicts"][record["verdict"]] = entry["verdicts"].get(record["verdict"], 0) + 1
        entry["contractProblems"].extend(record["contractProblems"])
        entry["durationMillis"].append(record["durationMillis"])
        metadata = case_metadata(cases_root / record["caseId"])
        entry["labels"] = metadata["labels"]
        entry["variants"] = metadata["variants"]

    levels: dict[str, dict] = {}
    for entry in cases.values():
        bucket = levels.setdefault(entry["level"], {"cases": 0, "runs": 0, "passedRuns": 0})
        bucket["cases"] += 1
        bucket["runs"] += entry["runs"]
        bucket["passedRuns"] += entry["passedRuns"]

    for entry in cases.values():
        durations = sorted(entry["durationMillis"])
        entry["durationMillisMedian"] = durations[len(durations) // 2]
        del entry["durationMillis"]

    verdicts: dict[str, int] = {}
    for record in records:
        verdicts[record["verdict"]] = verdicts.get(record["verdict"], 0) + 1

    return {
        "schemaVersion": 1,
        "mode": args.mode,
        "repeat": args.repeat,
        "generatedAtEpochMillis": int(time.time() * 1000),
        "totals": {
            "runs": len(records),
            "cases": len(cases),
            "passedRuns": sum(entry["passedRuns"] for entry in cases.values()),
            "verdicts": verdicts,
        },
        "levels": dict(sorted(levels.items())),
        "cases": [cases[key] for key in sorted(cases)],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="run_case.py", description="Run capability-ladder cases.")
    parser.add_argument(
        "--assets-dir",
        required=True,
        help="verified asset checkout; use fetch_assets.py to materialize the locked GitHub revision",
    )
    parser.add_argument("--case", required=True, help="case id, e.g. L1-01 (or 'all')")
    parser.add_argument("--mode", choices=MODES, default="nop")
    parser.add_argument(
        "--agent-command",
        default=None,
        help="agent command template containing {workspace} and optionally {prompt_file}",
    )
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--timeout-seconds", type=int, default=None, help="agent budget (default: case budget)")
    parser.add_argument(
        "--acceptance-timeout",
        type=int,
        default=DEFAULT_ACCEPTANCE_TIMEOUT_SECONDS,
        help="acceptance budget in seconds",
    )
    parser.add_argument("--work-root", default=None, help="directory for throwaway workspaces (default: temp)")
    parser.add_argument("--keep-workdir", action="store_true")
    parser.add_argument("--report", default=None, help="write the aggregated ladder report to this path")
    parser.add_argument("--json", action="store_true", help="print every run record")
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])
    cases_root, _ = verify_assets_root(Path(args.assets_dir))

    if args.case == "all":
        case_dirs = sorted(path for path in cases_root.glob("L*-*") if path.is_dir())
    else:
        case_dirs = [cases_root / args.case]
    missing = [path.name for path in case_dirs if not path.is_dir()]
    if missing:
        print(f"unknown case(s): {', '.join(missing)}", file=sys.stderr)
        return 2

    records = []
    for case_dir in case_dirs:
        for index in range(1, args.repeat + 1):
            record = run_once(case_dir, args, index)
            records.append(record)
            if args.json:
                print(json.dumps(record, ensure_ascii=False, sort_keys=True))
            else:
                print(
                    f"{record['caseId']} mode={record['mode']} verdict={record['verdict']} "
                    f"accepted={record['accepted']} expected={record['expected']} "
                    f"durationMillis={record['durationMillis']}"
                )

    if args.report:
        report_path = Path(args.report)
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(
            json.dumps(build_report(records, args, cases_root), ensure_ascii=False, sort_keys=True, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"RUN_CASE_REPORT {report_path}")

    unexpected = [record for record in records if record["verdict"] == "UNEXPECTED"]
    incomplete = [record for record in records if record["verdict"] == "INCOMPLETE_BUDGET"]
    print(
        f"RUN_CASE total={len(records)} unexpected={len(unexpected)} "
        f"incompleteBudget={len(incomplete)} mode={args.mode}"
    )
    if incomplete and not unexpected:
        return BUDGET_EXIT_CODE
    return 1 if unexpected else 0


if __name__ == "__main__":
    raise SystemExit(main())
