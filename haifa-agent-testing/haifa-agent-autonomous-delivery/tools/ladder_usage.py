#!/usr/bin/env python3
"""Resource usage of one capability-ladder evaluation, joined from its run records and runtime.db.

Used through ``run_ladder.py stats``. Every case run is matched to the root run the coding agent
persisted in its runtime database: the earliest root run created inside the agent's execution
window. Records written by current runners carry the exact window; older records fall back to the
agent log's modification time minus the agent duration.

The report holds counts, durations, token usage and stable failure codes only: no task statement,
no model output, no raw run identifier and no host path.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
from collections import Counter
from pathlib import Path

TOOLS_ROOT = Path(__file__).resolve().parent
REPOSITORY_ROOT = TOOLS_ROOT.parents[2]
LADDER_ROOT = REPOSITORY_ROOT / "local-tmp" / "autonomous-delivery-ladder"
DEFAULT_RUNTIME_DB = Path.home() / ".haifa-agent" / "coding" / "data" / "runtime.db"
USAGE_REPORT_NAME = "usage-report.json"
RECORDS_NAME = "run-records.jsonl"
MUTATING_TOOLS = frozenset({"file_patch", "file_create", "file_write", "file_delete", "file_move"})
# The agent's run row is created about two seconds after launch; the slack absorbs clock rounding.
WINDOW_SLACK_MILLIS = 2000


# ------------------------------------------------------------------------------------------ inputs


def resolve_run_dir(value: str | None, root: Path = LADDER_ROOT) -> Path:
    """Return the evaluation directory named by ``value``, or the newest one for ``latest``."""
    if value and value != "latest":
        run_dir = Path(value).expanduser().resolve()
        if not (run_dir / RECORDS_NAME).is_file():
            raise SystemExit(f"not an evaluation directory (no {RECORDS_NAME}): {run_dir}")
        return run_dir
    candidates = [path for path in root.glob("*") if (path / RECORDS_NAME).is_file()] if root.is_dir() else []
    if not candidates:
        raise SystemExit(f"no evaluation with {RECORDS_NAME} found under {root}")
    return max(candidates, key=lambda path: (path / RECORDS_NAME).stat().st_mtime)


def database_path_from_configuration(configuration: Path) -> Path | None:
    """Read ``persistence.databasePath`` from a distribution configuration, if it is concrete."""
    if not configuration.is_file():
        return None
    inside_persistence = False
    for line in configuration.read_text(encoding="utf-8").splitlines():
        if line and not line[0].isspace():
            inside_persistence = line.strip() == "persistence:"
            continue
        stripped = line.strip()
        if not (inside_persistence and stripped.startswith("databasePath:")):
            continue
        value = stripped.split(":", 1)[1].strip().strip("'\"")
        if value.startswith("${") and value.endswith("}"):
            name, _, fallback = value[2:-1].partition(":")
            value = os.environ.get(name, "").strip() or fallback
        if not value or value.startswith("__"):
            return None
        return Path(value)
    return None


def default_runtime_db(agent: str | None) -> Path:
    """The configured database of the agent distribution, then the environment, then the default."""
    if agent:
        configured = database_path_from_configuration(Path(agent).with_name("haifa-coding.yaml"))
        if configured:
            return configured
    explicit = os.environ.get("HAIFA_SQLITE_DATABASE_PATH", "").strip()
    return Path(explicit) if explicit else DEFAULT_RUNTIME_DB


def load_records(run_dir: Path) -> list[dict]:
    lines = (run_dir / RECORDS_NAME).read_text(encoding="utf-8").splitlines()
    return [json.loads(line) for line in lines if line.strip()]


def agent_window(record: dict, run_dir: Path) -> tuple[int, int] | None:
    """Return the agent's execution window in epoch milliseconds, exact when the record has it."""
    if record.get("mode") == "rehearse":
        return None
    start, end = record.get("agentStartedAtEpochMillis"), record.get("agentEndedAtEpochMillis")
    if isinstance(start, int) and isinstance(end, int) and end >= start:
        return start, end
    duration = record.get("agentDurationMillis")
    log = run_dir / "logs" / f"{record.get('caseId')}-{record.get('attempt', 1)}.log"
    if isinstance(duration, int) and log.is_file():
        end = int(log.stat().st_mtime * 1000)
        return end - duration, end
    return None


class BudgetLookup:
    """Resolve the agent budget of a record: recorded value first, then the pinned case.yaml."""

    def __init__(self, cache_dir: Path | None) -> None:
        self._cache_dir = cache_dir
        self._checkouts: dict[str, Path] | None = None

    def seconds(self, record: dict) -> tuple[int | None, str]:
        recorded = record.get("budgetSeconds")
        if isinstance(recorded, int) and recorded > 0:
            return recorded, "record"
        checkout = self._checkout(record.get("assetManifestSha256"))
        case_yaml = checkout / "cases" / str(record.get("caseId")) / "case.yaml" if checkout else None
        if case_yaml is None or not case_yaml.is_file():
            return None, "unknown"
        match = re.search(r"timeoutSeconds:\s*(\d+)", case_yaml.read_text(encoding="utf-8"))
        if not match:
            return None, "unknown"
        return int(match.group(1)), "case.yaml, timeout scale not recorded"

    def _checkout(self, digest: object) -> Path | None:
        if not isinstance(digest, str) or self._cache_dir is None:
            return None
        if self._checkouts is None:
            self._checkouts = {}
            for manifest in sorted(self._cache_dir.glob("assets-*/assets-manifest.json")):
                self._checkouts[hashlib.sha256(manifest.read_bytes()).hexdigest()] = manifest.parent
        return self._checkouts.get(digest)


# -------------------------------------------------------------------------------------- runtime.db


def open_database(path: Path) -> sqlite3.Connection | None:
    if not path.is_file():
        return None
    connection = sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    return connection


def find_run(connection: sqlite3.Connection, window: tuple[int, int], used: set[str]) -> sqlite3.Row | None:
    start, end = window
    rows = connection.execute(
        """select run_id, status, termination_reason, error_payload,
                  usage_input_tokens, usage_cached_input_tokens, usage_output_tokens,
                  usage_model_calls, usage_tool_calls
           from run
           where parent_run_id is null and created_at between ? and ?
           order by created_at""",
        (start - WINDOW_SLACK_MILLIS, end),
    ).fetchall()
    return next((row for row in rows if row["run_id"] not in used), None)


def error_code(payload: str | None) -> str | None:
    try:
        data = json.loads(payload) if payload else None
    except ValueError:
        return None
    code = data.get("code") if isinstance(data, dict) else None
    return str(code) if code else None


def model_call_stats(connection: sqlite3.Connection, run_id: str) -> dict:
    durations: list[int] = []
    failures = 0
    retries = 0
    finish_reasons: Counter = Counter()
    failure_codes: Counter = Counter()
    for kind, payload in connection.execute(
        """select type, data_payload from runtime_event
           where run_id=? and type in ('model.call.succeeded', 'model.call.failed', 'model.attempt.scheduled')""",
        (run_id,),
    ):
        try:
            values = (json.loads(payload) if payload else {}).get("values") or {}
        except (ValueError, AttributeError):
            continue
        if kind == "model.attempt.scheduled":
            if int(values.get("attempt") or 1) > 1:
                retries += 1
            continue
        if isinstance(values.get("durationMillis"), (int, float)):
            durations.append(int(values["durationMillis"]))
        if kind == "model.call.failed":
            failures += 1
            code = values.get("providerCode") or values.get("reasonCode")
            if code:
                failure_codes[str(code)] += 1
        elif values.get("finishReason"):
            finish_reasons[str(values["finishReason"])] += 1
    return {
        "modelCallFailures": failures,
        "modelRetries": retries,
        "modelMillis": sum(durations),
        "modelLatencySamples": len(durations),
        "modelAvgMillis": round(sum(durations) / len(durations)) if durations else None,
        "modelMaxMillis": max(durations) if durations else None,
        "finishReasons": dict(finish_reasons),
        "modelFailureCodes": dict(failure_codes),
    }


def tool_call_stats(connection: sqlite3.Connection, run_id: str) -> dict:
    statuses: Counter = Counter()
    millis = 0
    edits = 0
    for name, status, started, completed in connection.execute(
        "select tool_name, status, coalesce(started_at, requested_at), completed_at from tool_call where run_id=?",
        (run_id,),
    ):
        statuses[str(status)] += 1
        if started is not None and completed is not None and completed >= started:
            millis += completed - started
        if name in MUTATING_TOOLS and status == "COMPLETED":
            edits += 1
    return {
        "toolFailures": {state: statuses[state] for state in ("FAILED", "DENIED", "CANCELLED") if statuses[state]},
        "toolMillis": millis,
        "fileEdits": edits,
    }


def internal_end(row: sqlite3.Row | None, record: dict, budget_seconds: int | None, self_limit_ratio: float) -> str:
    """Classify how the agent run ended internally, independent of the acceptance verdict."""
    if row is None:
        return "NO_RUN"
    status, reason = row["status"], row["termination_reason"]
    if status == "COMPLETED":
        return "COMPLETED"
    if reason == "DEADLINE_EXCEEDED":
        return "DEADLINE"
    if status == "CANCELLED":
        wall = record.get("agentDurationMillis")
        if budget_seconds and isinstance(wall, int):
            self_limit = max(60, int(budget_seconds * self_limit_ratio)) * 1000
            if wall >= self_limit - WINDOW_SLACK_MILLIS:
                # Runtimes before typed cancellation record a CLI deadline as USER_CANCELLED.
                return "DEADLINE?"
        return "CANCELLED"
    if status == "FAILED":
        code = error_code(row["error_payload"])
        return f"FAILED:{code}" if code else "FAILED"
    return str(status)


# -------------------------------------------------------------------------------------- aggregation


def percent(part: float | None, whole: float | None) -> int | None:
    if part is None or not whole:
        return None
    return round(100 * part / whole)


def case_usage(
    connection: sqlite3.Connection | None,
    record: dict,
    run_dir: Path,
    budgets: BudgetLookup,
    used: set[str],
    self_limit_ratio: float,
) -> dict:
    budget, budget_source = budgets.seconds(record)
    wall = record.get("agentDurationMillis") if isinstance(record.get("agentDurationMillis"), int) else None
    entry: dict = {
        "caseId": record.get("caseId"),
        "attempt": record.get("attempt", 1),
        "level": record.get("level"),
        "result": record.get("status"),
        "accepted": bool(record.get("accepted")),
        "internalEnd": None,
        "terminationReason": None,
        "agentWallMillis": wall,
        "caseMillis": record.get("durationMillis"),
        "budgetSeconds": budget,
        "budgetSource": budget_source,
        "budgetUsedPercent": percent(wall, budget * 1000) if budget else None,
        "runRef": None,
        "modelCalls": None,
        "toolCalls": None,
        "tokens": None,
        "cacheHitPercent": None,
    }
    if record.get("mode") == "rehearse":
        entry["internalEnd"] = "REHEARSAL"
        return entry
    if connection is None:
        entry["internalEnd"] = "UNAVAILABLE"
        return entry
    window = agent_window(record, run_dir)
    row = find_run(connection, window, used) if window else None
    entry["internalEnd"] = internal_end(row, record, budget, self_limit_ratio)
    if row is None:
        return entry
    used.add(row["run_id"])
    input_tokens = int(row["usage_input_tokens"] or 0)
    cached_tokens = int(row["usage_cached_input_tokens"] or 0)
    output_tokens = int(row["usage_output_tokens"] or 0)
    entry.update(
        {
            "terminationReason": row["termination_reason"],
            "runRef": hashlib.sha256(str(row["run_id"]).encode("utf-8")).hexdigest()[:12],
            "modelCalls": int(row["usage_model_calls"] or 0),
            "toolCalls": int(row["usage_tool_calls"] or 0),
            "tokens": {
                "total": input_tokens + output_tokens,
                "input": input_tokens,
                "cachedInput": cached_tokens,
                "output": output_tokens,
            },
            "cacheHitPercent": percent(cached_tokens, input_tokens),
        }
    )
    entry.update(model_call_stats(connection, row["run_id"]))
    entry.update(tool_call_stats(connection, row["run_id"]))
    return entry


def summarize(entries: list[dict]) -> dict:
    matched = [entry for entry in entries if entry["runRef"]]
    passed = sum(1 for entry in entries if entry["accepted"])

    def total(key: str) -> int:
        return sum(entry.get(key) or 0 for entry in matched)

    tokens = {
        key: sum((entry["tokens"] or {}).get(key, 0) for entry in matched)
        for key in ("total", "input", "cachedInput", "output")
    }
    wall = sum(entry["agentWallMillis"] or 0 for entry in entries)
    model_millis = total("modelMillis")
    samples = total("modelLatencySamples")
    tool_failures: Counter = Counter()
    failure_codes: Counter = Counter()
    for entry in matched:
        tool_failures.update(entry.get("toolFailures") or {})
        failure_codes.update(entry.get("modelFailureCodes") or {})
    levels: dict[str, dict] = {}
    for entry in entries:
        bucket = levels.setdefault(str(entry["level"]), {"cases": 0, "passed": 0, "agentWallMillis": 0, "tokens": 0})
        bucket["cases"] += 1
        bucket["passed"] += 1 if entry["accepted"] else 0
        bucket["agentWallMillis"] += entry["agentWallMillis"] or 0
        bucket["tokens"] += (entry["tokens"] or {}).get("total", 0)
    return {
        "cases": len(entries),
        "passed": passed,
        "passRatePercent": percent(passed, len(entries)),
        "internalEnds": dict(Counter(entry["internalEnd"] for entry in entries)),
        "matchedRuns": len(matched),
        "unmatched": [entry["caseId"] for entry in entries if entry["internalEnd"] == "NO_RUN"],
        "agentWallMillis": wall,
        "modelMillis": model_millis,
        "modelTimePercent": percent(model_millis, wall),
        "toolMillis": total("toolMillis"),
        "toolTimePercent": percent(total("toolMillis"), wall),
        "modelCalls": total("modelCalls"),
        "modelCallFailures": total("modelCallFailures"),
        "modelRetries": total("modelRetries"),
        "modelAvgMillis": round(model_millis / samples) if samples else None,
        "modelMaxMillis": max((entry.get("modelMaxMillis") or 0 for entry in matched), default=None) or None,
        "modelFailureCodes": dict(failure_codes),
        "toolCalls": total("toolCalls"),
        "toolFailures": dict(tool_failures),
        "fileEdits": total("fileEdits"),
        "tokens": tokens,
        "cacheHitPercent": percent(tokens["cachedInput"], tokens["input"]),
        "tokensPerPassedCase": round(tokens["total"] / passed) if passed else None,
        "nearBudgetCases": [
            entry["caseId"] for entry in entries if (entry["budgetUsedPercent"] or 0) >= 85
        ],
        "levels": dict(sorted(levels.items())),
    }


def build_usage_report(
    run_dir: Path,
    runtime_db: Path,
    cache_dir: Path | None,
    self_limit_ratio: float,
) -> dict:
    records = load_records(run_dir)
    connection = open_database(runtime_db)
    budgets = BudgetLookup(cache_dir)
    used: set[str] = set()
    try:
        entries = [case_usage(connection, record, run_dir, budgets, used, self_limit_ratio) for record in records]
    finally:
        if connection is not None:
            connection.close()
    first = records[0] if records else {}
    return {
        "schemaVersion": 1,
        "evaluation": {
            "run": run_dir.name,
            "mode": first.get("mode"),
            "model": first.get("model"),
            "modelSource": first.get("modelSource"),
            "approval": first.get("approval"),
            "assetVersion": first.get("assetVersion"),
            "assetsPinned": first.get("assetsPinned"),
        },
        "runtimeDatabaseAvailable": connection is not None,
        "totals": summarize(entries),
        "cases": entries,
    }


# ------------------------------------------------------------------------------------------ render


def human_tokens(value: int | None) -> str:
    if value is None:
        return "-"
    if value >= 1_000_000:
        return f"{value / 1_000_000:.2f}M"
    if value >= 10_000:
        return f"{value / 1000:.0f}k"
    if value >= 1_000:
        return f"{value / 1000:.1f}k"
    return str(value)


def human_duration(millis: int | None) -> str:
    if millis is None:
        return "-"
    seconds = int(millis // 1000)
    if seconds < 60:
        return f"{seconds}s"
    if seconds < 3600:
        return f"{seconds // 60}m{seconds % 60:02d}s"
    return f"{seconds // 3600}h{(seconds % 3600) // 60:02d}m"


def cell(value: object) -> str:
    return "-" if value is None else str(value)


ROW = (
    "{case:<9} {result:<17} {internal:<36} {wall:>7} {budget:>6} {model:>5} {mfail:>5} {retry:>5} "
    "{avg:>7} {tools:>5} {tfail:>5} {edits:>5} {total:>7} {input:>7} {cached:>7} {output:>7} {cache:>6}"
)


def render_table(report: dict) -> str:
    evaluation, totals = report["evaluation"], report["totals"]
    lines = [
        f"LADDER_USAGE run={evaluation['run']} mode={evaluation['mode']} model={evaluation['model']} "
        f"({evaluation['modelSource']}) assets={evaluation['assetVersion']} "
        f"({'pinned' if evaluation['assetsPinned'] else 'UNPINNED'})",
    ]
    if not report["runtimeDatabaseAvailable"]:
        lines.append("  runtime.db not found: model, tool and token usage are unavailable (pass --runtime-db)")
    lines.append("")
    lines.append(
        ROW.format(
            case="case", result="result", internal="internal end", wall="wall", budget="budg%",
            model="model", mfail="mfail", retry="retry", avg="avg-lat", tools="tools", tfail="tfail",
            edits="edits", total="tokens", input="input", cached="cached", output="output", cache="cache%",
        )
    )
    for entry in report["cases"]:
        tokens = entry["tokens"] or {}
        suffix = f".{entry['attempt']}" if entry["attempt"] != 1 else ""
        lines.append(
            ROW.format(
                case=f"{entry['caseId']}{suffix}",
                result=("PASSED" if entry["accepted"] else cell(entry["result"]))[:17],
                internal=cell(entry["internalEnd"])[:36],
                wall=human_duration(entry["agentWallMillis"]),
                budget=cell(entry["budgetUsedPercent"]),
                model=cell(entry["modelCalls"]),
                mfail=cell(entry.get("modelCallFailures")),
                retry=cell(entry.get("modelRetries")),
                avg=human_duration(entry.get("modelAvgMillis")),
                tools=cell(entry["toolCalls"]),
                tfail=cell(sum((entry.get("toolFailures") or {}).values()) if entry["runRef"] else None),
                edits=cell(entry.get("fileEdits")),
                total=human_tokens(tokens.get("total")),
                input=human_tokens(tokens.get("input")),
                cached=human_tokens(tokens.get("cachedInput")),
                output=human_tokens(tokens.get("output")),
                cache=cell(entry["cacheHitPercent"]),
            )
        )
    tokens = totals["tokens"]
    ends = ", ".join(f"{name} {count}" for name, count in sorted(totals["internalEnds"].items()))
    lines += [
        "",
        "LADDER_USAGE_SUMMARY",
        f"  passed {totals['passed']}/{totals['cases']} ({cell(totals['passRatePercent'])}%)   internal end: {ends}",
        f"  agent wall {human_duration(totals['agentWallMillis'])} | model time {human_duration(totals['modelMillis'])} "
        f"({cell(totals['modelTimePercent'])}%) | tool time {human_duration(totals['toolMillis'])} "
        f"({cell(totals['toolTimePercent'])}%)",
        f"  model calls {totals['modelCalls']} (failed {totals['modelCallFailures']}, retries {totals['modelRetries']}) "
        f"avg latency {human_duration(totals['modelAvgMillis'])} max {human_duration(totals['modelMaxMillis'])}",
        f"  tool calls {totals['toolCalls']} (failures {sum(totals['toolFailures'].values())}"
        f"{' ' + str(totals['toolFailures']) if totals['toolFailures'] else ''}) file edits {totals['fileEdits']}",
        f"  tokens {human_tokens(tokens['total'])} = input {human_tokens(tokens['input'])} "
        f"(cached {human_tokens(tokens['cachedInput'])}, {cell(totals['cacheHitPercent'])}%) "
        f"+ output {human_tokens(tokens['output'])} | per passed case {human_tokens(totals['tokensPerPassedCase'])}",
    ]
    if totals["modelFailureCodes"]:
        lines.append(f"  model failure codes: {totals['modelFailureCodes']}")
    if totals["nearBudgetCases"]:
        lines.append(f"  near budget (>=85%): {', '.join(totals['nearBudgetCases'])}")
    if totals["unmatched"]:
        lines.append(f"  no agent run found: {', '.join(totals['unmatched'])}")
    level_text = " | ".join(
        f"{level} {bucket['passed']}/{bucket['cases']} {human_tokens(bucket['tokens'])}"
        for level, bucket in totals["levels"].items()
    )
    lines.append(f"  by level: {level_text}")
    return "\n".join(lines)


def main(
    run_value: str | None,
    runtime_db: str | None,
    cache_dir: Path | None,
    agent: str | None,
    self_limit_ratio: float,
    as_json: bool,
    emit=print,
) -> int:
    run_dir = resolve_run_dir(run_value)
    database = Path(runtime_db).expanduser() if runtime_db else default_runtime_db(agent)
    report = build_usage_report(run_dir, database, cache_dir, self_limit_ratio)
    report_path = run_dir / USAGE_REPORT_NAME
    report_path.write_text(json.dumps(report, ensure_ascii=True, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    if as_json:
        emit(json.dumps(report, ensure_ascii=True, sort_keys=True))
    else:
        emit(render_table(report))
        emit(f"  report: {report_path}")
    return 0
