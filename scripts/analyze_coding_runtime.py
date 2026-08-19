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


REPORT_SCHEMA_VERSION = "1.1.0"
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
GIT_KNOWN_READ_PATTERN = re.compile(
    r"(?i)^\s*git(?:\.exe)?\s+(?:--no-pager\s+|--no-optional-locks\s+|--literal-pathspecs\s+)*"
    r"(?:status|diff|log|show|blame|grep|ls-files|rev-parse|symbolic-ref|for-each-ref|describe)(?:\s|$)"
)
HARD_BOUNDARY_CODES = {
    "ABSOLUTE_WORKDIR_FORBIDDEN",
    "AUTHENTICATION_COMMAND_DENIED",
    "AUTHENTICATION_ENVIRONMENT_OVERRIDE",
    "GH_AUTHENTICATION_MUTATION_OR_DISCLOSURE",
    "GH_AUTH_TOKEN_DISCLOSURE_DENIED",
    "GH_PR_MERGE_DENIED",
    "GIT_AUTHENTICATION_CONFIG_OVERRIDE",
    "GIT_CREDENTIAL_PROTOCOL_DENIED",
    "GIT_EXECUTION_BOUNDARY_OVERRIDE",
    "GIT_REPOSITORY_PATH_OVERRIDE",
    "SYSTEM_CLI_PATH_OVERRIDE",
    "PATH_TRAVERSAL",
    "SENSITIVE_PATH",
    "SYMLINK_ESCAPE",
    "WORKSPACE_PATH_ESCAPE",
}


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


def _failure_class(tool_name: str, stable_code: str, failure_category: str) -> str:
    if stable_code in HARD_BOUNDARY_CODES:
        return "HARD_BOUNDARY_DENIAL"
    if stable_code == "COMMAND_CLASSIFICATION_REJECTED":
        return "POLICY_OR_CLASSIFICATION"
    if failure_category in {"POLICY", "POLICY_DENIED"}:
        return "POLICY_DENIAL"
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


def _rate(numerator: int, denominator: int, numerator_name: str = "count") -> dict[str, Any]:
    return {
        "status": "MEASURED",
        numerator_name: numerator,
        "total": denominator,
        "ratePercent": _percentage(numerator, denominator),
    }


def _unavailable(reason: str) -> dict[str, str]:
    return {"status": "UNAVAILABLE", "reason": reason}


def _is_composite(command: Any) -> bool:
    if not isinstance(command, str):
        return False
    return any(token in command for token in (";", "&&", "||", "|", ">", "<", "\n", "\r", "`")) or bool(
        re.match(r"(?is)^\s*(?:powershell|pwsh|cmd|bash|sh|env)\b", command)
    )


def _effective_risk(result: dict[str, Any], attributes: dict[str, Any]) -> str | None:
    effective = _safe_text(result.get("effectiveRisk"), _safe_text(attributes.get("effectiveRisk"), ""))
    if effective in {"LOW", "MEDIUM", "HIGH"}:
        return effective
    raw = _safe_text(result.get("commandRisk"), _safe_text(attributes.get("commandRisk"), ""))
    return {
        "LOCAL_READ": "LOW",
        "LOCAL_WRITE": "MEDIUM",
        "NETWORK_READ": "MEDIUM",
        "EXTERNAL_WRITE": "HIGH",
        "DESTRUCTIVE": "HIGH",
        "UNKNOWN": "HIGH",
    }.get(raw)


def _required_metrics_empty() -> dict[str, Any]:
    no_samples = _unavailable("NO_SAMPLES_IN_WINDOW")
    return {
        "rawToolFailureRate": _rate(0, 0, "failed"),
        "policyDenialRate": _rate(0, 0, "denied"),
        "hardBoundaryDenialRate": _rate(0, 0, "denied"),
        "riskEscalationDistribution": dict(no_samples),
        "approvalAskAllowRateByThreshold": _unavailable("APPROVAL_DECISIONS_NOT_IN_REQUIRED_SOURCE_SCHEMA"),
        "compositeCommandAdmissionCompletionRate": _rate(0, 0, "admitted"),
        "commandSemanticFailureRate": _rate(0, 0, "failed"),
        "toolInfrastructureFailureRate": _rate(0, 0, "failed"),
        "gitKnownReadLowClassificationRate": _rate(0, 0, "classifiedLow"),
        "gitExpectedExitFalseFailureRate": _rate(0, 0, "falseFailures"),
        "sameFingerprintRetryAmplification": dict(no_samples),
        "runCompletionFailedCancelled": {"status": "MEASURED", "counts": {}},
        "modelEmptyOutputRecoveryRate": _unavailable("MODEL_ATTEMPT_TRACE_NOT_IN_REQUIRED_SOURCE_SCHEMA"),
        "contextPreflightRejectionCompactionRate": _unavailable(
            "CONTEXT_PREFLIGHT_TRACE_NOT_IN_REQUIRED_SOURCE_SCHEMA"
        ),
        "timeModelToolCallsToFirstMeaningfulChange": dict(no_samples),
        "deliveryEvidenceCompleteness": _unavailable("FROZEN_DELIVERY_INTENT_NOT_IN_REQUIRED_SOURCE_SCHEMA"),
        "resumeStateConsistencyRate": _unavailable("RESUME_COMPARISON_TRACE_NOT_IN_REQUIRED_SOURCE_SCHEMA"),
        "costKnownUnknown": {"status": "UNKNOWN", "reason": "COST_NOT_IN_REQUIRED_SOURCE_SCHEMA"},
    }


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
            "requiredMetrics": _required_metrics_empty(),
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
    policy_denials = 0
    legacy_classification_denials = 0
    hard_boundary_denials = 0
    risk_distribution: Counter[str] = Counter()
    composite_total = 0
    composite_admitted = 0
    composite_completed = 0
    semantic_failures = 0
    infrastructure_failures = 0
    known_read_total = 0
    known_read_low = 0
    expected_exit_candidates = 0
    expected_exit_total = 0
    expected_exit_false_failures = 0
    expected_exit_unobservable = 0
    first_change_by_run: dict[str, tuple[int, int]] = {}
    calls_by_run: Counter[str] = Counter()
    delivery_evidence: Counter[str] = Counter()
    for row in tool_rows:
        tool_name = str(row["tool_name"])
        status = _safe_text(row["status"])
        tool_counter.setdefault(tool_name, Counter())[status] += 1
        arguments = _values(row["arguments_payload"])
        result = _structured_data(row["result_payload"])
        error = _json_object(row["error_payload"])
        attributes = _attributes(row["error_payload"])
        command = arguments.get("command")
        calls_by_run[str(row["run_id"])] += 1
        target = _safe_text(result.get("commandTarget"), _command_target(command))
        operation = _safe_text(result.get("commandOperation"), _safe_text(arguments.get("operationFamily")))
        risk = _safe_text(result.get("commandRisk"))
        if tool_name == "execution_run":
            execution_counter[(target, operation, risk, status)] += 1
            effective_risk = _effective_risk(result, attributes)
            if effective_risk:
                risk_distribution[effective_risk] += 1
            if _is_composite(command):
                composite_total += 1
                stable = _safe_text(
                    result.get("stableFailureCode"),
                    _safe_text(attributes.get("stableFailureCode"), _safe_text(error.get("code"), "")),
                )
                if stable != "COMMAND_CLASSIFICATION_REJECTED":
                    composite_admitted += 1
                if status == "COMPLETED":
                    composite_completed += 1
            if isinstance(command, str) and GIT_KNOWN_READ_PATTERN.match(command):
                known_read_total += 1
                if effective_risk == "LOW" or risk == "LOCAL_READ":
                    known_read_low += 1
            semantic_outcome = _safe_text(result.get("semanticOutcome"), "")
            if semantic_outcome == "COMMAND_FAILED":
                semantic_failures += 1
            expected_exit_candidate = bool(
                isinstance(command, str)
                and re.match(r"(?is)^\s*git(?:\.exe)?\s+(?:diff\b.*(?:--exit-code|--no-index)|grep\b)", command)
            )
            if expected_exit_candidate:
                expected_exit_candidates += 1
            if expected_exit_candidate and result.get("exitCode") == 1:
                expected_exit_total += 1
                if status == "FAILED" and semantic_outcome != "EXPECTED_VARIANT":
                    expected_exit_false_failures += 1
        evidence_code = _safe_text(result.get("deliveryEvidenceCode"), "")
        if evidence_code:
            delivery_evidence[evidence_code] += 1
        if ("fileChangeSetId" in result or "changeSetId" in result) and str(row["run_id"]) not in first_change_by_run:
            first_change_by_run[str(row["run_id"])] = (
                int(row["requested_at"]),
                calls_by_run[str(row["run_id"])],
            )
        if status != "FAILED":
            continue
        stable_code = _safe_text(
            result.get("stableFailureCode"),
            _safe_text(
                attributes.get("stableFailureCode"),
                _safe_text(error.get("code"), "UNAVAILABLE"),
            ),
        )
        if tool_name == "execution_run" and expected_exit_candidate and not isinstance(result.get("exitCode"), int):
            expected_exit_unobservable += 1
        failure_category = _safe_text(
            result.get("failureCategory"), _safe_text(attributes.get("failureCategory"))
        )
        if failure_category in {"POLICY", "POLICY_DENIED"} or stable_code == "COMMAND_CLASSIFICATION_REJECTED":
            policy_denials += 1
        if stable_code == "COMMAND_CLASSIFICATION_REJECTED":
            legacy_classification_denials += 1
        if stable_code in HARD_BOUNDARY_CODES:
            hard_boundary_denials += 1
        failure_class = _failure_class(tool_name, stable_code, failure_category)
        failure_classes[failure_class] += 1
        if tool_name == "execution_run" and failure_class == "COMMAND_NON_ZERO" and semantic_outcome != "COMMAND_FAILED":
            semantic_failures += 1
        if failure_class == "TOOL_INFRASTRUCTURE":
            infrastructure_failures += 1
        failed_tool_calls.append(
            {
                "runDigest": _identity_digest(row["run_id"]),
                "toolCallDigest": _identity_digest(row["tool_call_id"]),
                "toolName": tool_name,
                "stableFailureCode": stable_code,
                "failureCategory": failure_category,
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
    retry_observations = 0
    amplified_attempts = 0
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
            retry_observations += 1
            amplified_attempts += max(0, attempts - 1)

    total_tools = len(tool_rows)
    failed_tools = sum(1 for row in tool_rows if _safe_text(row["status"]) == "FAILED")
    execution_total = sum(execution_counter.values())
    run_statuses = _status_counts(run_rows)
    first_change_metrics: dict[str, Any]
    if first_change_by_run:
        run_starts = {
            str(row["run_id"]): int(row["started_at"])
            for row in connection.execute(
                """
                SELECT run_id, min(occurred_at) AS started_at
                FROM runtime_event
                WHERE occurred_at > ? AND occurred_at <= ?
                GROUP BY run_id
                """,
                window_parameters,
            )
        }
        elapsed = [
            max(0, changed_at - run_starts[run_id])
            for run_id, (changed_at, _) in first_change_by_run.items()
            if run_id in run_starts
        ]
        call_counts = [calls for _, calls in first_change_by_run.values()]
        first_change_metrics = {
            "status": "PARTIALLY_MEASURED",
            "runsWithMeaningfulChange": len(first_change_by_run),
            "timeMillis": {
                "minimum": min(elapsed) if elapsed else None,
                "maximum": max(elapsed) if elapsed else None,
                "average": round(sum(elapsed) / len(elapsed), 2) if elapsed else None,
            },
            "toolCalls": {
                "minimum": min(call_counts),
                "maximum": max(call_counts),
                "average": round(sum(call_counts) / len(call_counts), 2),
            },
            "modelCalls": _unavailable("MODEL_CALLS_NOT_IN_REQUIRED_SOURCE_SCHEMA"),
        }
    else:
        first_change_metrics = _unavailable("NO_AUTHORITATIVE_CHANGE_OBSERVED")

    risk_metric: dict[str, Any] = (
        {
            "status": "MEASURED" if sum(risk_distribution.values()) == execution_total else "PARTIALLY_MEASURED",
            "counts": {level: risk_distribution.get(level, 0) for level in ("LOW", "MEDIUM", "HIGH")},
            "total": sum(risk_distribution.values()),
            "executionCalls": execution_total,
            "unclassified": execution_total - sum(risk_distribution.values()),
        }
        if risk_distribution
        else _unavailable("TRUSTED_EFFECTIVE_RISK_NOT_PRESENT")
    )
    retry_metric: dict[str, Any] = (
        {
            "status": "MEASURED",
            "observations": retry_observations,
            "amplifiedAttempts": amplified_attempts,
            "maximumAttempts": maximum_attempts,
        }
        if retry_observations
        else _unavailable("FAILURE_FINGERPRINT_ATTEMPTS_NOT_PRESENT")
    )
    empty_output_failures = sum(count for (code, _), count in run_failures.items() if code == "MODEL_RESPONSE_INVALID")
    context_failures = sum(
        count
        for (code, _), count in run_failures.items()
        if code in {"REQUIRED_CONTEXT_TOO_LARGE", "MODEL_CONTEXT_TOO_LONG"}
    )
    required_metrics = {
        "rawToolFailureRate": _rate(failed_tools, total_tools, "failed"),
        "policyDenialRate": {
            "status": "MEASURED" if legacy_classification_denials == 0 else "PARTIALLY_MEASURED",
            "denied": policy_denials,
            "legacyClassificationIncluded": legacy_classification_denials,
            "total": total_tools,
            "ratePercent": _percentage(policy_denials, total_tools),
        },
        "hardBoundaryDenialRate": _rate(hard_boundary_denials, total_tools, "denied"),
        "riskEscalationDistribution": risk_metric,
        "approvalAskAllowRateByThreshold": _unavailable("APPROVAL_DECISIONS_NOT_IN_REQUIRED_SOURCE_SCHEMA"),
        "compositeCommandAdmissionCompletionRate": {
            "status": "MEASURED",
            "total": composite_total,
            "admitted": composite_admitted,
            "admissionRatePercent": _percentage(composite_admitted, composite_total),
            "completed": composite_completed,
            "completionRatePercent": _percentage(composite_completed, composite_total),
        },
        "commandSemanticFailureRate": _rate(semantic_failures, execution_total, "failed"),
        "toolInfrastructureFailureRate": _rate(infrastructure_failures, total_tools, "failed"),
        "gitKnownReadLowClassificationRate": _rate(known_read_low, known_read_total, "classifiedLow"),
        "gitExpectedExitFalseFailureRate": {
            "status": "MEASURED" if expected_exit_unobservable == 0 else "PARTIALLY_MEASURED",
            "candidates": expected_exit_candidates,
            "observableExitOne": expected_exit_total,
            "unobservableExitCode": expected_exit_unobservable,
            "falseFailures": expected_exit_false_failures,
            "ratePercent": (
                _percentage(expected_exit_false_failures, expected_exit_total) if expected_exit_total else None
            ),
        },
        "sameFingerprintRetryAmplification": retry_metric,
        "runCompletionFailedCancelled": {"status": "MEASURED", "counts": run_statuses},
        "modelEmptyOutputRecoveryRate": {
            "status": "UNAVAILABLE",
            "observedTerminalFailures": empty_output_failures,
            "reason": "MODEL_ATTEMPT_TRACE_NOT_IN_REQUIRED_SOURCE_SCHEMA",
        },
        "contextPreflightRejectionCompactionRate": {
            "status": "UNAVAILABLE",
            "observedTerminalContextFailures": context_failures,
            "reason": "CONTEXT_PREFLIGHT_TRACE_NOT_IN_REQUIRED_SOURCE_SCHEMA",
        },
        "timeModelToolCallsToFirstMeaningfulChange": first_change_metrics,
        "deliveryEvidenceCompleteness": {
            "status": "UNAVAILABLE",
            "observedEvidenceCounts": dict(sorted(delivery_evidence.items())),
            "reason": "FROZEN_DELIVERY_INTENT_NOT_IN_REQUIRED_SOURCE_SCHEMA",
        },
        "resumeStateConsistencyRate": _unavailable("RESUME_COMPARISON_TRACE_NOT_IN_REQUIRED_SOURCE_SCHEMA"),
        "costKnownUnknown": {"status": "UNKNOWN", "reason": "COST_NOT_IN_REQUIRED_SOURCE_SCHEMA"},
    }

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
        "runStatuses": run_statuses,
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
        "requiredMetrics": required_metrics,
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
