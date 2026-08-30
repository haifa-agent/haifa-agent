#!/usr/bin/env python3
"""Summarize safe Issue 29 tool-latency fields from a CLI Runtime Trace JSONL file."""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Iterable


STAGES = (
    ("toolElapsedMs", "end-to-end"),
    ("environmentAcquireMs", "environment acquire"),
    ("providerInvocationMs", "provider invocation"),
    ("outputValidationMs", "output validation"),
    ("resultJournalMs", "pending-result journal"),
    ("resultNormalizationMs", "result normalization"),
    ("resultExternalizationMs", "result externalization"),
    ("resultPersistenceMs", "terminal persistence"),
)


def percentile(values: Iterable[int], fraction: float) -> int:
    ordered = sorted(values)
    if not ordered:
        raise ValueError("percentile requires at least one value")
    index = max(0, min(len(ordered) - 1, int((len(ordered) * fraction + 0.9999999999)) - 1))
    return ordered[index]


def summarize(records: Iterable[dict[str, Any]]) -> tuple[dict[str, dict[str, list[int]]], int]:
    grouped: dict[str, dict[str, list[int]]] = defaultdict(lambda: defaultdict(list))
    selected = 0
    for record in records:
        if record.get("operation") != "tool.persisted":
            continue
        attributes = record.get("attributes")
        if not isinstance(attributes, dict) or "toolElapsedMs" not in attributes:
            continue
        tool_name = attributes.get("toolName")
        if not isinstance(tool_name, str) or not tool_name:
            tool_name = "unknown-tool"
        selected += 1
        for field, _ in STAGES:
            value = attributes.get(field)
            if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
                grouped[tool_name][field].append(value)
    return grouped, selected


def render_markdown(grouped: dict[str, dict[str, list[int]]], sample_count: int, trace: Path) -> str:
    lines = [
        "# Issue 29 P0 tool-latency baseline",
        "",
        f"Generated (UTC): {datetime.now(UTC).replace(microsecond=0).isoformat()}",
        f"Trace: `{trace.name}`",
        f"Completed tool samples with monotonic timing: {sample_count}",
        "",
        "The trace contains only approved bounded timing fields; it does not include paths, arguments, file contents, prompts, or credentials.",
        "",
    ]
    if not grouped:
        lines.extend(["No completed timing samples were found.", ""])
        return "\n".join(lines)
    lines.extend(
        [
            "| Tool | Stage | Samples | P50 ms | P95 ms | Max ms | Mean ms |",
            "| --- | --- | ---: | ---: | ---: | ---: | ---: |",
        ]
    )
    for tool_name in sorted(grouped):
        for field, label in STAGES:
            values = grouped[tool_name].get(field, [])
            if not values:
                continue
            mean = sum(values) / len(values)
            lines.append(
                f"| {tool_name} | {label} | {len(values)} | {percentile(values, 0.50)} | "
                f"{percentile(values, 0.95)} | {max(values)} | {mean:.1f} |"
            )
    lines.extend(
        [
            "",
            "Interpretation: provider invocation includes the local Project Tool operation; correlate an outlying persistence stage with the existing `sqlite.uow` phase logs before changing durability or recovery behavior.",
            "",
        ]
    )
    return "\n".join(lines)


def read_jsonl(trace: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for number, line in enumerate(trace.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"invalid JSONL at line {number}: {error.msg}") from error
        if not isinstance(value, dict):
            raise ValueError(f"invalid JSONL object at line {number}")
        records.append(value)
    return records


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path, help="CLI Runtime Trace JSONL file")
    parser.add_argument("--output", type=Path, help="write Markdown report to this file instead of stdout")
    args = parser.parse_args(argv)
    trace = args.trace.resolve()
    if not trace.is_file():
        parser.error(f"trace must be an existing regular file: {trace}")
    try:
        grouped, sample_count = summarize(read_jsonl(trace))
    except ValueError as error:
        parser.error(str(error))
    rendered = render_markdown(grouped, sample_count, trace)
    if args.output is None:
        sys.stdout.write(rendered)
    else:
        output = args.output.resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
