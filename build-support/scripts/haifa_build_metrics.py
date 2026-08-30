#!/usr/bin/env python3
"""Run Maven with resource-aware defaults and emit a safe, comparable metric record."""

from __future__ import annotations

import argparse
import ctypes
import datetime as dt
import json
import os
import platform
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


CLASSIFICATIONS = {
    "PASS",
    "TEST_FAILURE",
    "COMPILE_FAILURE",
    "BUILD_CONFIGURATION_FAILURE",
    "OUTER_TIMEOUT_PROCESS_EXIT_UNKNOWN",
    "HOST_RESOURCE_OOM",
    "HOST_SLEEP_CONTAMINATED",
    "CANCELLED",
}
SENSITIVE_ARGUMENT = re.compile(r"(?i)(api[-_.]?key|token|password|secret|credential)=")
REACTOR_LINE = re.compile(
    r"^\[INFO\]\s+(.+?)\s+\.{2,}\s+(SUCCESS|FAILURE|SKIPPED)\s+\[\s*([^\]]+)\s*\]\s*$"
)
BUILD_POSITION = re.compile(r"\[(\d+)/(\d+)\]")
BUILDING_MODULE = re.compile(r"^\[INFO\] Building .+")
TOTAL_TIME = re.compile(r"^\[INFO\] Total time:\s+(.+?)\s*$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the repository Maven Wrapper and write one safe JSON metric record."
    )
    parser.add_argument("--layer", choices=("L0", "L1", "L2", "L3"), default="L1")
    parser.add_argument("--threads", type=int, default=0)
    parser.add_argument("--timeout-seconds", type=int, default=0)
    parser.add_argument("--metrics-root", default="")
    parser.add_argument("--no-metrics", action="store_true")
    parser.add_argument("--keep-log", action="store_true")
    parser.add_argument(
        "--stream-output",
        action="store_true",
        help="Stream Maven output to the console. Disabled by default to avoid AI/CI pipe backpressure.",
    )
    parser.add_argument("maven_args", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.maven_args[:1] == ["--"]:
        args.maven_args = args.maven_args[1:]
    if args.threads < 0 or args.threads > 64:
        parser.error("--threads must be between 0 and 64")
    if args.timeout_seconds < 0:
        parser.error("--timeout-seconds must not be negative")
    if not args.maven_args:
        parser.error("Maven arguments are required after --")
    return args


def repository_root() -> Path:
    root = Path(__file__).resolve().parents[2]
    wrapper = root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    if not wrapper.is_file():
        raise SystemExit(f"Maven Wrapper was not found below {root}")
    return root


def run_text(command: list[str], cwd: Path) -> str:
    try:
        completed = subprocess.run(
            command,
            cwd=cwd,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=15,
            check=False,
            encoding="utf-8",
            errors="replace",
        )
        return completed.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return "unavailable"


def git_facts(root: Path) -> tuple[str, bool]:
    sha = run_text(["git", "rev-parse", "HEAD"], root).splitlines()[0]
    dirty = bool(run_text(["git", "status", "--porcelain", "--untracked-files=normal"], root))
    return sha, dirty


def maven_version(root: Path) -> str:
    properties = root / ".mvn" / "wrapper" / "maven-wrapper.properties"
    if properties.is_file():
        match = re.search(r"apache-maven-([0-9.]+)-bin", properties.read_text(encoding="utf-8"))
        if match:
            return match.group(1)
    return "unknown"


def java_version(root: Path) -> str:
    output = run_text(["java", "-version"], root)
    first = output.splitlines()[0] if output else "unavailable"
    match = re.search(r'"([^"]+)"', first)
    return match.group(1) if match else first


def memory_snapshot() -> dict[str, int | None]:
    if os.name == "nt":
        class MemoryStatus(ctypes.Structure):
            _fields_ = [
                ("length", ctypes.c_ulong),
                ("memoryLoad", ctypes.c_ulong),
                ("totalPhysical", ctypes.c_ulonglong),
                ("availablePhysical", ctypes.c_ulonglong),
                ("totalPageFile", ctypes.c_ulonglong),
                ("availablePageFile", ctypes.c_ulonglong),
                ("totalVirtual", ctypes.c_ulonglong),
                ("availableVirtual", ctypes.c_ulonglong),
                ("availableExtendedVirtual", ctypes.c_ulonglong),
            ]

        status = MemoryStatus()
        status.length = ctypes.sizeof(status)
        if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
            return {
                "totalPhysicalBytes": status.totalPhysical,
                "availablePhysicalBytes": status.availablePhysical,
                "totalCommitBytes": status.totalPageFile,
                "availableCommitBytes": status.availablePageFile,
            }
    if Path("/proc/meminfo").is_file():
        values: dict[str, int] = {}
        for line in Path("/proc/meminfo").read_text(encoding="utf-8").splitlines():
            key, _, raw = line.partition(":")
            number = raw.strip().split()[0]
            if number.isdigit():
                values[key] = int(number) * 1024
        return {
            "totalPhysicalBytes": values.get("MemTotal"),
            "availablePhysicalBytes": values.get("MemAvailable"),
            "totalCommitBytes": values.get("CommitLimit"),
            "availableCommitBytes": None,
        }
    return {
        "totalPhysicalBytes": None,
        "availablePhysicalBytes": None,
        "totalCommitBytes": None,
        "availableCommitBytes": None,
    }


def default_threads(layer: str) -> int:
    if layer == "L2":
        return 4
    if layer == "L3":
        return 2
    return 1


def has_thread_argument(arguments: list[str]) -> bool:
    return any(value == "-T" or value.startswith("-T") or value.startswith("--threads") for value in arguments)


def effective_arguments(layer: str, threads: int, arguments: list[str]) -> tuple[list[str], int]:
    chosen = threads or default_threads(layer)
    effective = list(arguments)
    if not has_thread_argument(effective):
        effective[0:0] = ["-T", str(chosen)]
    else:
        chosen = 0
    if "--batch-mode" not in effective and "-B" not in effective:
        effective.insert(0, "--batch-mode")
    if "--no-transfer-progress" not in effective and "-ntp" not in effective:
        effective.insert(1, "--no-transfer-progress")
    return effective, chosen


def redacted(arguments: list[str]) -> list[str]:
    result: list[str] = []
    for value in arguments:
        if SENSITIVE_ARGUMENT.search(value):
            result.append(value.split("=", 1)[0] + "=<redacted>")
        else:
            result.append(value)
    return result


def terminate_tree(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(process.pid), "/T", "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    else:
        try:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=5)
        except (ProcessLookupError, subprocess.TimeoutExpired):
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass


def safe_console_write(line: str) -> bool:
    try:
        sys.stdout.write(line)
        sys.stdout.flush()
        return True
    except UnicodeEncodeError:
        encoding = sys.stdout.encoding or "utf-8"
        safe_line = line.encode(encoding, errors="replace").decode(encoding)
        try:
            sys.stdout.write(safe_line)
            sys.stdout.flush()
            return True
        except (BrokenPipeError, OSError):
            return False
    except (BrokenPipeError, OSError):
        return False


def pump_output(process: subprocess.Popen[str], log_path: Path, stream_output: bool) -> None:
    assert process.stdout is not None
    console_available = stream_output
    with log_path.open("w", encoding="utf-8", newline="") as log:
        for line in process.stdout:
            log.write(line)
            if console_available:
                console_available = safe_console_write(line)


def parse_duration(value: str) -> int | None:
    value = value.strip()
    match = re.fullmatch(r"(?:(\d+):)?(\d+):(\d+(?:\.\d+)?)\s+min", value)
    if match:
        hours = int(match.group(1) or 0)
        return round((hours * 3600 + int(match.group(2)) * 60 + float(match.group(3))) * 1000)
    match = re.fullmatch(r"(\d+(?:\.\d+)?)\s+s", value)
    if match:
        return round(float(match.group(1)) * 1000)
    match = re.fullmatch(r"(\d+)\s+ms", value)
    if match:
        return int(match.group(1))
    return None


def parse_maven_log(log_path: Path) -> dict[str, Any]:
    reactor_projects = 0
    maven_reported_millis: int | None = None
    modules: list[dict[str, Any]] = []
    building_modules = 0
    failure_position: float | None = None
    lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
    for line in lines:
        if BUILDING_MODULE.match(line):
            building_modules += 1
        position = BUILD_POSITION.search(line)
        if position:
            current, total = int(position.group(1)), int(position.group(2))
            reactor_projects = max(reactor_projects, total)
            if "FAILURE" in line or "ERROR" in line:
                failure_position = round(current / total, 4)
        total_time = TOTAL_TIME.match(line)
        if total_time:
            maven_reported_millis = parse_duration(total_time.group(1))
        module = REACTOR_LINE.match(line)
        if module:
            millis = parse_duration(module.group(3))
            modules.append(
                {
                    "module": module.group(1).strip(),
                    "status": module.group(2),
                    "millis": millis,
                }
            )
    if not reactor_projects and modules:
        reactor_projects = len(modules)
    if not reactor_projects:
        reactor_projects = building_modules
    slowest = sorted(
        (item for item in modules if item["millis"] is not None),
        key=lambda item: item["millis"],
        reverse=True,
    )[:10]
    return {
        "reactorProjects": reactor_projects,
        "mavenReportedMillis": maven_reported_millis,
        "failureBuildPosition": failure_position,
        "slowestModules": slowest,
        "logText": "\n".join(lines),
    }


def test_report_facts(root: Path, started_epoch: float) -> tuple[dict[str, int], list[dict[str, Any]]]:
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "forkReportFiles": 0}
    classes: list[dict[str, Any]] = []
    ignored_dirs = {"node_modules", ".git", ".idea", ".vscode", "dist", "build"}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ignored_dirs and not d.startswith(".")]
        base = os.path.basename(dirpath)
        if base in ("surefire-reports", "failsafe-reports"):
            for fname in filenames:
                if fname.startswith("TEST-") and fname.endswith(".xml"):
                    report = Path(dirpath) / fname
                    try:
                        if report.stat().st_mtime < started_epoch - 2:
                            continue
                        suite = ET.parse(report).getroot()
                        item = {
                            "testClass": suite.attrib.get("name", report.stem.removeprefix("TEST-")),
                            "millis": round(float(suite.attrib.get("time", "0")) * 1000),
                            "tests": int(float(suite.attrib.get("tests", "0"))),
                            "failures": int(float(suite.attrib.get("failures", "0"))),
                            "errors": int(float(suite.attrib.get("errors", "0"))),
                            "skipped": int(float(suite.attrib.get("skipped", "0"))),
                        }
                    except (OSError, ET.ParseError, ValueError):
                        continue
                    totals["forkReportFiles"] += 1
                    for key in ("tests", "failures", "errors", "skipped"):
                        totals[key] += item[key]
                    classes.append(item)
    classes.sort(key=lambda item: item["millis"], reverse=True)
    return totals, classes[:10]


def classify(exit_code: int, output: str, timed_out: bool, cancelled: bool, slept: bool) -> str:
    lowered = output.lower()
    if cancelled:
        result = "CANCELLED"
    elif timed_out:
        result = "OUTER_TIMEOUT_PROCESS_EXIT_UNKNOWN"
    elif "native memory allocation" in lowered or "outofmemoryerror" in lowered or "error occurred during initialization of vm" in lowered:
        result = "HOST_RESOURCE_OOM"
    elif exit_code == 0:
        result = "PASS"
    elif "compilation failure" in lowered or "compilation error" in lowered:
        result = "COMPILE_FAILURE"
    elif "there are test failures" in lowered or re.search(
        r"tests run:\s*\d+,\s*failures:\s*[1-9]\d*|tests run:\s*\d+,.+errors:\s*[1-9]\d*",
        lowered,
    ):
        result = "TEST_FAILURE"
    else:
        result = "BUILD_CONFIGURATION_FAILURE"
    if slept and result == "PASS":
        result = "HOST_SLEEP_CONTAMINATED"
    assert result in CLASSIFICATIONS
    return result


def host_sleep_detected(
    root: Path,
    started_at: dt.datetime,
    ended_at: dt.datetime,
    wall_seconds: float,
    monotonic_seconds: float,
) -> bool:
    if wall_seconds - monotonic_seconds > 5.0:
        return True
    if os.name != "nt":
        return False
    script = (
        "$start=[DateTimeOffset]::Parse($args[0]).LocalDateTime;"
        "$end=[DateTimeOffset]::Parse($args[1]).LocalDateTime;"
        "$events=Get-WinEvent -FilterHashtable @{LogName='System';StartTime=$start;EndTime=$end} "
        "-ErrorAction Stop | Where-Object {$_.Id -in 1,42,107,506,507};"
        "if($events){'true'}else{'false'}"
    )
    output = run_text(
        ["powershell", "-NoProfile", "-NonInteractive", "-Command", script, started_at.isoformat(), ended_at.isoformat()],
        root,
    )
    return output.splitlines()[-1:] == ["true"]


def main() -> int:
    args = parse_args()
    root = repository_root()
    wrapper = root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    effective, chosen_threads = effective_arguments(args.layer, args.threads, args.maven_args)
    metrics_root = (
        Path(args.metrics_root).expanduser().resolve()
        if args.metrics_root
        else root / "local-tmp" / "maven-build-metrics"
    )
    metrics_root.mkdir(parents=True, exist_ok=True)
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    git_sha, dirty = git_facts(root)
    identifier = f"{timestamp}-{args.layer.lower()}-{git_sha[:8]}-{os.getpid()}"
    descriptor, temporary_name = tempfile.mkstemp(prefix=identifier + "-", suffix=".log", dir=metrics_root)
    os.close(descriptor)
    temporary_log = Path(temporary_name)
    started_at = dt.datetime.now(dt.timezone.utc)
    started_epoch = time.time()
    started_wall = time.time()
    started_monotonic = time.monotonic()
    memory_before = memory_snapshot()
    creation_flags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
    process = subprocess.Popen(
        [str(wrapper), *effective],
        cwd=root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        encoding="utf-8",
        errors="replace",
        creationflags=creation_flags,
        start_new_session=os.name != "nt",
    )
    pump = threading.Thread(
        target=pump_output,
        args=(process, temporary_log, args.stream_output),
        daemon=True,
    )
    pump.start()
    timed_out = False
    cancelled = False
    try:
        process.wait(timeout=args.timeout_seconds or None)
    except subprocess.TimeoutExpired:
        timed_out = True
        terminate_tree(process)
    except KeyboardInterrupt:
        cancelled = True
        terminate_tree(process)
    finally:
        pump.join(timeout=10)
    exit_code = process.returncode if process.returncode is not None else 124
    if timed_out:
        exit_code = 124
    elif cancelled:
        exit_code = 130
    ended_at = dt.datetime.now(dt.timezone.utc)
    wall_seconds = time.time() - started_wall
    monotonic_seconds = time.monotonic() - started_monotonic
    slept = host_sleep_detected(root, started_at, ended_at, wall_seconds, monotonic_seconds)
    parsed = parse_maven_log(temporary_log)
    output = parsed.pop("logText")
    test_totals, slowest_tests = test_report_facts(root, started_epoch)
    classification = classify(exit_code, output, timed_out, cancelled, slept)
    record = {
        "schemaVersion": 1,
        "gitSha": git_sha,
        "dirty": dirty,
        "os": platform.system().lower(),
        "osRelease": platform.release(),
        "javaVersion": java_version(root),
        "mavenVersion": maven_version(root),
        "layer": args.layer,
        "profiles": [value[2:] for value in effective if value.startswith("-P")],
        "arguments": redacted(effective),
        "threads": chosen_threads,
        "clean": "clean" in effective,
        "startedAt": started_at.isoformat(),
        "endedAt": ended_at.isoformat(),
        "wallMillis": round(wall_seconds * 1000),
        "activeMonotonicMillis": round(monotonic_seconds * 1000),
        "hostSleepDetected": slept,
        "exitCode": exit_code,
        "classification": classification,
        "retryAttempt": int(os.environ.get("HAIFA_MAVEN_RETRY_ATTEMPT", "0") or 0),
        "hostMemoryBefore": memory_before,
        "hostMemoryAfter": memory_snapshot(),
        "testSummary": test_totals,
        "slowestTests": slowest_tests,
        **parsed,
    }
    if not args.no_metrics:
        metric_path = metrics_root / f"{identifier}.json"
        metric_path.write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(
            f"HAIFA_MAVEN_RESULT={classification} exit={exit_code} "
            f"wallMillis={record['wallMillis']} tests={test_totals['tests']}"
        )
        print(f"HAIFA_MAVEN_METRIC={metric_path}")
    if args.keep_log:
        kept_log = metrics_root / f"{identifier}.maven.log"
        shutil.move(temporary_log, kept_log)
        print(f"HAIFA_MAVEN_LOG={kept_log}")
    else:
        temporary_log.unlink(missing_ok=True)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
