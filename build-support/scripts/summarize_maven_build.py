#!/usr/bin/env python3
"""Summarize comparable local Maven build metric records."""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections import Counter
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Summarize local Maven build metrics.")
    parser.add_argument("--metrics-root", default="")
    parser.add_argument("--limit", type=int, default=100)
    parser.add_argument("--git-sha", default="")
    parser.add_argument("--layer", choices=("L0", "L1", "L2", "L3"), default="")
    args = parser.parse_args()
    if not 1 <= args.limit <= 10000:
        parser.error("--limit must be an integer from 1 to 10000")
    return args


def repository_root() -> Path:
    return Path(__file__).resolve().parents[2]


def load_records(metrics_root: Path, limit: int) -> list[dict[str, Any]]:
    if not metrics_root.is_dir():
        raise RuntimeError(f"Metrics directory does not exist: {metrics_root}")
    paths = sorted(
        metrics_root.glob("*.json"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )[:limit]
    records: list[dict[str, Any]] = []
    for path in paths:
        try:
            value = json.loads(path.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError) as error:
            raise RuntimeError(f"Unable to read Maven metric record {path}: {error}") from error
        if not isinstance(value, dict):
            raise RuntimeError(f"Maven metric record must be a JSON object: {path}")
        records.append(value)
    return records


def percentile(values: list[int], fraction: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(fraction * len(ordered)) - 1)
    return ordered[index]


def grouped_maximum(records: list[dict[str, Any]], field: str, name: str) -> list[dict[str, Any]]:
    maximums: dict[str, int] = {}
    for record in records:
        rows = record.get(field) or []
        if not isinstance(rows, list):
            continue
        for row in rows:
            if not isinstance(row, dict) or not row.get(name) or row.get("millis") is None:
                continue
            key = str(row[name])
            maximums[key] = max(maximums.get(key, 0), int(row["millis"]))
    return [
        {name: key, "maxMillis": millis}
        for key, millis in sorted(maximums.items(), key=lambda item: item[1], reverse=True)[:10]
    ]


def summarize(records: list[dict[str, Any]]) -> dict[str, Any]:
    clean = [record for record in records if not record.get("hostSleepDetected", False)]
    passing = [record for record in clean if record.get("classification") == "PASS"]
    wall = [int(record["wallMillis"]) for record in passing if record.get("wallMillis") is not None]
    classifications = Counter(str(record.get("classification", "")) for record in records)
    return {
        "schemaVersion": 1,
        "sampleCount": len(records),
        "comparableCount": len(clean),
        "passingCount": len(passing),
        "classifications": [
            {"classification": name, "count": classifications[name]}
            for name in sorted(classifications)
        ],
        "p50WallMillis": percentile(wall, 0.50),
        "p95WallMillis": percentile(wall, 0.95),
        "maxWallMillis": max(wall) if wall else None,
        "slowestModules": grouped_maximum(passing, "slowestModules", "module"),
        "slowestTests": grouped_maximum(passing, "slowestTests", "testClass"),
    }


def main() -> int:
    try:
        args = parse_args()
        metrics_root = (
            Path(args.metrics_root).expanduser().resolve()
            if args.metrics_root
            else repository_root() / "local-tmp" / "maven-build-metrics"
        )
        records = load_records(metrics_root, args.limit)
        records = [
            record
            for record in records
            if (not args.git_sha or record.get("gitSha") == args.git_sha)
            and (not args.layer or record.get("layer") == args.layer)
        ]
        if not records:
            raise RuntimeError("No matching Maven metric records were found.")
        print(json.dumps(summarize(records), indent=2, ensure_ascii=False))
        return 0
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Maven build summary failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
