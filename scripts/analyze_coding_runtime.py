#!/usr/bin/env python3

"""Produce a privacy-bounded reliability report from a Haifa Coding SQLite store."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import sqlite3
import tempfile
from collections import Counter
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


REPORT_SCHEMA_VERSION = "1.0.0"
REQUIRED_COLUMNS = {
    "runtime_event": {"run_id", "type", "data_payload", "occurred_at"},
    "run": {"run_id", "session_id", "status", "error_payload"},
    "tool_call": {
        "tool_call_id",
        "run_id",
        "tool_name",
        "arguments_payload",
        "status",
        "result_payload",
        "error_payload",
        "requested_at",
    },
}
GIT_PATTERN = re.compile(r"(?i)(?:^|[\s;&|()])git(?:\.exe)?(?=\s|$)")
GH_PATTERN = re.compile(r"(?i)(?:^|[\s;&|()])gh(?:\.exe)?(?=\s|$)")


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Analyze a Haifa Coding Agent runtime.db without emitting prompts, "
            "credentials, command text, raw provider responses, or host paths."
        )
    )
    parser.add_argument("--data-root", required=True, help="Coding data directory containing runtime.db.")
    parser.add_argument("--latest-hours", required=True, type=float, help="Window size ending at the latest runtime event.")
    parser.add_argument("--output", required=True, help="New JSON report path outside the source and docs repositories.")
    return parser.parse_args()


def _inside(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def resolve_paths(data_root_value: str, output_value: str) -> tuple[Path, Path]:
    data_root = Path(data_root_value).expanduser().resolve()
    if not data_root.is_dir():
        raise ValueError("--data-root must resolve to an existing directory")
    database = data_root / "runtime.db"
    if not database.is_file():
        raise ValueError("--data-root must contain runtime.db")

    output = Path(output_value).expanduser().resolve()
    repository_root = Path(__file__).resolve().parent.parent
    docs_root = repository_root / "docs"
    for protected_root, label in (
        (data_root, "data root"),
        (repository_root, "source repository"),
        (docs_root, "docs repository"),
    ):
        if _inside(output, protected_root):
            raise ValueError(f"--output must be outside the {label}")
    if output.exists():
        raise ValueError("--output already exists; refusing to overwrite evidence")
    return database, output


def connect_read_only(database: Path) -> sqlite3.Connection:
    uri = f"file:{database.as_posix()}?mode=ro"
    connection = sqlite3.connect(uri, uri=True, timeout=5.0)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA query_only = ON")
    connection.execute("PRAGMA busy_timeout = 5000")
    return connection


def _columns(connection: sqlite3.Connection, table: str) -> set[str]:
    return {str(row["name"]) for row in connection.execute(f'PRAGMA table_info("{table}")')}


def validate_schema(connection: sqlite3.Connection) -> None:
    tables = {
        str(row["name"])
        for row in connection.execute("SELECT name FROM sqlite_master WHERE type = 'table'")
    }
    for table, required in REQUIRED_COLUMNS.items():
        if table not in tables:
            raise ValueError(f"unsupported runtime schema: missing table {table}")
        missing = required - _columns(connection, table)
        if missing:
            names = ", ".join(sorted(missing))
            raise ValueError(f"unsupported runtime schema: {table} is missing {names}")


def _json_object(payload: Any) -> dict[str, Any]:
    if payload is None:
        return {}
    if isinstance(payload, memoryview):
        payload = payload.tobytes()
    if isinstance(payload, bytes):
        payload = payload.decode("utf-8")
    try:
        value = json.loads(payload)
    except (json.JSONDecodeError, TypeError, UnicodeDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def _values(payload: Any) -> dict[str, Any]:
    value = _json_object(payload).get("values", {})
    return value if isinstance(value, dict) else {}


def _structured_data(payload: Any) -> dict[str, Any]:
    value = _json_object(payload).get("structuredData", {})
    return value if isinstance(value, dict) else {}


def _attributes(payload: Any) -> dict[str, Any]:
    value = _json_object(payload).get("attributes", {})
    return value if isinstance(value, dict) else {}


def _safe_text(value: Any, default: str = "UNAVAILABLE") -> str:
    if not isinstance(value, str):
        return default
    normalized = value.strip().upper()
    return normalized if normalized and re.fullmatch(r"[A-Z0-9_.-]+", normalized) else default


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _identity_digest(value: Any) -> str:
    return _digest(str(value))[:16]


def _command_digest(command: Any) -> str | None:
    if not isinstance(command, str) or not command.strip():
        return None
    normalized = " ".join(command.split())
    return _digest(normalized)


def _command_target(command: Any) -> str:
    if not isinstance(command, str):
        return "OTHER"
    git = GIT_PATTERN.search(command) is not None
    github = GH_PATTERN.search(command) is not None
    if git and github:
        return "MIXED"
    if git:
        return "GIT"
    if github:
        return "GITHUB"
    return "OTHER"


def _failure_class(tool_name: str, stable_code: str) -> str:
    if stable_code == "COMMAND_CLASSIFICATION_REJECTED":
        return "POLICY_OR_CLASSIFICATION"
    if stable_code in {"NON_ZERO_EXIT", "COMMAND_FAILED"}:
        return "COMMAND_NON_ZERO"
    if tool_name.startswith("file_"):
        return "WORKSPACE_FILE_OPERATION"
    return "TOOL_INFRASTRUCTURE"


def _iso_utc(epoch_millis: int) -> str:
    return datetime.fromtimestamp(epoch_millis / 1000, timezone.utc).isoformat().replace("+00:00", "Z")


def _status_counts(rows: Iterable[sqlite3.Row], key: str = "status") -> dict[str, int]:
    counts = Counter(_safe_text(row[key]) for row in rows)
    return dict(sorted(counts.items()))


def _percentage(numerator: int, denominator: int) -> float:
    return round((numerator / denominator) * 100, 2) if denominator else 0.0


def analyze(connection: sqlite3.Connection, latest_hours: float) -> dict[str, Any]:
    if not math.isfinite(latest_hours) or latest_hours <= 0 or latest_hours > 24 * 365:
        raise ValueError("--latest-hours must be greater than 0 and no more than 8760")
    validate_schema(connection)

    end_value = connection.execute("SELECT max(occurred_at) AS value FROM runtime_event").fetchone()["value"]
    if end_value is None:
        return {
            "schemaVersion": REPORT_SCHEMA_VERSION,
            "window": None,
            "scope": {"sessions": 0, "runs": 0, "toolCalls": 0},
            "runStatuses": {},
            "runFailures": [],
            "toolStatuses": {},
            "toolMetrics": [],
            "failureClasses": {},
            "failedToolCalls": [],
            "executionBreakdown": [],
            "recovery": {"eventsByType": {}, "directives": {}, "maximumAttempts": 0},
            "privacy": _privacy_contract(),
        }

    end_millis = int(end_value)
    duration_millis = int(latest_hours * 60 * 60 * 1000)
    start_millis = end_millis - duration_millis
    window_parameters = (start_millis, end_millis)

    run_rows = list(
        connection.execute(
            """
            SELECT r.run_id, r.session_id, r.status, r.error_payload
            FROM run r
            JOIN (
              SELECT DISTINCT run_id FROM runtime_event
              WHERE occurred_at > ? AND occurred_at <= ?
            ) wr ON wr.run_id = r.run_id
            ORDER BY r.run_id
            """,
            window_parameters,
        )
    )
    tool_rows = list(
        connection.execute(
            """
            SELECT tool_call_id, run_id, tool_name, arguments_payload, status,
                   result_payload, error_payload, requested_at
            FROM tool_call
            WHERE requested_at > ? AND requested_at <= ?
            ORDER BY requested_at, tool_call_id
            """,
            window_parameters,
        )
    )
    event_rows = list(
        connection.execute(
            """
            SELECT run_id, type, data_payload, occurred_at
            FROM runtime_event
            WHERE occurred_at > ? AND occurred_at <= ?
              AND type IN (
                'tool.failure-cluster-updated',
                'tool.recovery-strategy-required',
                'loop.stall-detected'
              )
            ORDER BY occurred_at, sequence
            """,
            window_parameters,
        )
    )

    failed_tool_calls: list[dict[str, Any]] = []
    execution_counter: Counter[tuple[str, str, str, str]] = Counter()
    tool_counter: dict[str, Counter[str]] = {}
    failure_classes: Counter[str] = Counter()
    for row in tool_rows:
        tool_name = str(row["tool_name"])
        status = _safe_text(row["status"])
        tool_counter.setdefault(tool_name, Counter())[status] += 1
        arguments = _values(row["arguments_payload"])
        result = _structured_data(row["result_payload"])
        error = _json_object(row["error_payload"])
        attributes = _attributes(row["error_payload"])
        command = arguments.get("command")
        target = _safe_text(result.get("commandTarget"), _command_target(command))
        operation = _safe_text(result.get("commandOperation"), _safe_text(arguments.get("operationFamily")))
        risk = _safe_text(result.get("commandRisk"))
        if tool_name == "execution_run":
            execution_counter[(target, operation, risk, status)] += 1
        if status != "FAILED":
            continue
        stable_code = _safe_text(
            attributes.get("stableFailureCode"),
            _safe_text(error.get("code"), "UNAVAILABLE"),
        )
        failure_class = _failure_class(tool_name, stable_code)
        failure_classes[failure_class] += 1
        failed_tool_calls.append(
            {
                "runDigest": _identity_digest(row["run_id"]),
                "toolCallDigest": _identity_digest(row["tool_call_id"]),
                "toolName": tool_name,
                "stableFailureCode": stable_code,
                "failureCategory": _safe_text(attributes.get("failureCategory")),
                "failureClass": failure_class,
                "commandTarget": target if tool_name == "execution_run" else "NOT_APPLICABLE",
                "operationFamily": _safe_text(attributes.get("operationFamily"), operation),
                "commandDigest": _command_digest(command),
                "requestedAtEpochMs": int(row["requested_at"]),
            }
        )

    tool_metrics = []
    for tool_name, counts in sorted(tool_counter.items()):
        total = sum(counts.values())
        failures = counts.get("FAILED", 0)
        tool_metrics.append(
            {
                "toolName": tool_name,
                "statuses": dict(sorted(counts.items())),
                "total": total,
                "failed": failures,
                "failureRatePercent": _percentage(failures, total),
            }
        )

    run_failures: Counter[tuple[str, str]] = Counter()
    for row in run_rows:
        if _safe_text(row["status"]) != "FAILED":
            continue
        error = _json_object(row["error_payload"])
        run_failures[(_safe_text(error.get("code")), _safe_text(error.get("category")))] += 1

    recovery_types: Counter[str] = Counter()
    recovery_directives: Counter[str] = Counter()
    maximum_attempts = 0
    for row in event_rows:
        event_type = str(row["type"])
        recovery_types[event_type] += 1
        values = _values(row["data_payload"])
        directive = _safe_text(values.get("directive"), "")
        if directive:
            recovery_directives[directive] += 1
        attempts = values.get("attempts")
        if isinstance(attempts, int):
            maximum_attempts = max(maximum_attempts, attempts)

    return {
        "schemaVersion": REPORT_SCHEMA_VERSION,
        "window": {
            "source": "MAX_RUNTIME_EVENT",
            "latestHours": latest_hours,
            "startEpochMsExclusive": start_millis,
            "endEpochMsInclusive": end_millis,
            "startUtcExclusive": _iso_utc(start_millis),
            "endUtcInclusive": _iso_utc(end_millis),
        },
        "scope": {
            "sessions": len({str(row["session_id"]) for row in run_rows}),
            "runs": len(run_rows),
            "toolCalls": len(tool_rows),
        },
        "runStatuses": _status_counts(run_rows),
        "runFailures": [
            {"code": code, "category": category, "count": count}
            for (code, category), count in sorted(run_failures.items())
        ],
        "toolStatuses": _status_counts(tool_rows),
        "toolMetrics": tool_metrics,
        "failureClasses": dict(sorted(failure_classes.items())),
        "failedToolCalls": failed_tool_calls,
        "executionBreakdown": [
            {
                "commandTarget": target,
                "operationFamily": operation,
                "commandRisk": risk,
                "status": status,
                "count": count,
            }
            for (target, operation, risk, status), count in sorted(execution_counter.items())
        ],
        "recovery": {
            "eventsByType": dict(sorted(recovery_types.items())),
            "directives": dict(sorted(recovery_directives.items())),
            "maximumAttempts": maximum_attempts,
        },
        "privacy": _privacy_contract(),
    }


def _privacy_contract() -> dict[str, Any]:
    return {
        "containsPrompt": False,
        "containsCommandText": False,
        "containsCredential": False,
        "containsRawProviderResponse": False,
        "containsAbsolutePath": False,
        "identifiers": "SHA256_PREFIX",
        "commands": "SHA256_NORMALIZED",
    }


def write_report(report: dict[str, Any], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".tmp", dir=output.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2, sort_keys=True)
            stream.write("\n")
        try:
            os.link(temporary, output)
        except FileExistsError as error:
            raise ValueError("--output already exists; refusing to overwrite evidence") from error
    finally:
        temporary.unlink(missing_ok=True)


def main() -> int:
    arguments = parse_arguments()
    try:
        database, output = resolve_paths(arguments.data_root, arguments.output)
        with closing(connect_read_only(database)) as connection:
            connection.execute("BEGIN")
            report = analyze(connection, arguments.latest_hours)
            connection.rollback()
        write_report(report, output)
    except (OSError, sqlite3.Error, ValueError) as error:
        print(f"analyze-coding-runtime: {error}", file=os.sys.stderr)
        return 2
    print(f"analyze-coding-runtime: wrote schema {REPORT_SCHEMA_VERSION} report")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
