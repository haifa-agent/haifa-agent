#!/usr/bin/env python3
"""Pause or resume the VS Code JDT language server for one workspace."""

from __future__ import annotations

import argparse
import ctypes
import json
import os
import shutil
import signal
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse
from urllib.request import url2pathname


JDT_MARKERS = (
    "-Declipse.product=org.eclipse.jdt.ls.core.product",
    "-Declipse.application=org.eclipse.jdt.ls.core.id1",
)
STATE_RELATIVE_PATH = Path("local-tmp") / "java-language-server-control.json"


@dataclass(frozen=True)
class ProcessInfo:
    pid: int
    command_line: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Pause, resume, or report the VS Code Red Hat Java language server "
            "associated with one workspace."
        )
    )
    parser.add_argument("action", type=str.lower, choices=("stop", "start", "status"))
    parser.add_argument(
        "--workspace",
        default=str(Path(__file__).resolve().parents[2]),
        help="Workspace directory; defaults to this repository.",
    )
    parser.add_argument("--start-wait-seconds", type=int, default=15)
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.start_wait_seconds <= 60:
        parser.error("--start-wait-seconds must be between 1 and 60")
    return args


def normalized_path(path: Path | str) -> str:
    resolved = str(Path(path).expanduser().resolve())
    return os.path.normcase(os.path.normpath(resolved))


def unique_existing_directories(paths: list[Path]) -> list[Path]:
    result: list[Path] = []
    seen: set[str] = set()
    for path in paths:
        key = normalized_path(path)
        if key not in seen and path.is_dir():
            seen.add(key)
            result.append(path)
    return result


def workspace_storage_roots() -> list[Path]:
    override = os.environ.get("HAIFA_VSCODE_WORKSPACE_STORAGE", "")
    if override:
        return unique_existing_directories(
            [Path(value) for value in override.split(os.pathsep) if value]
        )

    home = Path.home()
    if os.name == "nt":
        app_data = Path(os.environ.get("APPDATA", home / "AppData" / "Roaming"))
        candidates = [
            app_data / "Code" / "User" / "workspaceStorage",
            app_data / "Code - Insiders" / "User" / "workspaceStorage",
            app_data / "VSCodium" / "User" / "workspaceStorage",
        ]
    elif sys.platform == "darwin":
        application_support = home / "Library" / "Application Support"
        candidates = [
            application_support / "Code" / "User" / "workspaceStorage",
            application_support / "Code - Insiders" / "User" / "workspaceStorage",
            application_support / "VSCodium" / "User" / "workspaceStorage",
        ]
    else:
        config_home = Path(os.environ.get("XDG_CONFIG_HOME", home / ".config"))
        candidates = [
            config_home / "Code" / "User" / "workspaceStorage",
            config_home / "Code - Insiders" / "User" / "workspaceStorage",
            config_home / "VSCodium" / "User" / "workspaceStorage",
            config_home / "code-oss" / "User" / "workspaceStorage",
            home / ".vscode-server" / "data" / "User" / "workspaceStorage",
            home / ".vscode-server-insiders" / "data" / "User" / "workspaceStorage",
        ]
    return unique_existing_directories(candidates)


def path_from_file_uri(value: str) -> Path | None:
    parsed = urlparse(value)
    if parsed.scheme.lower() != "file":
        return None
    uri_path = unquote(parsed.path)
    if parsed.netloc:
        uri_path = f"//{parsed.netloc}{uri_path}"
    return Path(url2pathname(uri_path))


def storage_paths_for_workspace(workspace: Path) -> list[Path]:
    workspace_key = normalized_path(workspace)
    matches: list[Path] = []
    for root in workspace_storage_roots():
        for storage_path in root.iterdir():
            metadata_path = storage_path / "workspace.json"
            if not metadata_path.is_file():
                continue
            try:
                metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
                folder_uri = metadata.get("folder")
                folder_path = path_from_file_uri(folder_uri) if isinstance(folder_uri, str) else None
                if folder_path is not None and normalized_path(folder_path) == workspace_key:
                    matches.append(storage_path)
            except (OSError, ValueError, TypeError):
                continue
    return matches


def windows_processes() -> list[ProcessInfo]:
    executable = shutil.which("powershell.exe") or shutil.which("pwsh.exe") or shutil.which("pwsh")
    if not executable:
        raise RuntimeError("PowerShell is required to enumerate Java processes on Windows.")
    script = (
        "$ErrorActionPreference='Stop';"
        "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);"
        "$items=@(Get-CimInstance Win32_Process | "
        "Where-Object {$_.Name -in @('java.exe','javaw.exe')} | "
        "Select-Object ProcessId,CommandLine);"
        "ConvertTo-Json -InputObject $items -Compress"
    )
    completed = subprocess.run(
        [executable, "-NoProfile", "-NonInteractive", "-Command", script],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=15,
        check=False,
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or "unknown PowerShell error"
        raise RuntimeError(f"Unable to enumerate Windows processes: {detail}")
    raw_items = json.loads(completed.stdout or "[]")
    if isinstance(raw_items, dict):
        raw_items = [raw_items]
    return [
        ProcessInfo(int(item["ProcessId"]), str(item.get("CommandLine") or ""))
        for item in raw_items
    ]


def posix_processes() -> list[ProcessInfo]:
    completed = subprocess.run(
        ["ps", "-ww", "-axo", "pid=,command="],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=15,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"Unable to enumerate processes: {completed.stderr.strip()}")
    result: list[ProcessInfo] = []
    for line in completed.stdout.splitlines():
        parts = line.strip().split(None, 1)
        if len(parts) == 2 and parts[0].isdigit():
            result.append(ProcessInfo(int(parts[0]), parts[1]))
    return result


def all_processes() -> list[ProcessInfo]:
    return windows_processes() if os.name == "nt" else posix_processes()


def language_server_processes(storage_paths: list[Path]) -> list[ProcessInfo]:
    jdt_workspace_paths = [normalized_path(path / "redhat.java" / "jdt_ws") for path in storage_paths]
    result: list[ProcessInfo] = []
    for process in all_processes():
        command_lower = process.command_line.lower()
        if not any(marker.lower() in command_lower for marker in JDT_MARKERS):
            continue
        normalized_command = os.path.normcase(os.path.normpath(process.command_line))
        if any(path in normalized_command for path in jdt_workspace_paths):
            result.append(process)
    return result


def windows_process_operation(pid: int, resume: bool) -> None:
    process_suspend_resume = 0x0800
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    ntdll = ctypes.WinDLL("ntdll")
    kernel32.OpenProcess.argtypes = (ctypes.c_uint32, ctypes.c_bool, ctypes.c_uint32)
    kernel32.OpenProcess.restype = ctypes.c_void_p
    kernel32.CloseHandle.argtypes = (ctypes.c_void_p,)
    kernel32.CloseHandle.restype = ctypes.c_bool
    operation = ntdll.NtResumeProcess if resume else ntdll.NtSuspendProcess
    operation.argtypes = (ctypes.c_void_p,)
    operation.restype = ctypes.c_long

    handle = kernel32.OpenProcess(process_suspend_resume, False, pid)
    if not handle:
        error = ctypes.get_last_error()
        raise OSError(error, f"Unable to open process {pid}")
    try:
        status = operation(handle)
        if status != 0:
            name = "NtResumeProcess" if resume else "NtSuspendProcess"
            raise OSError(f"{name} failed for process {pid} with NTSTATUS 0x{status & 0xFFFFFFFF:08X}")
    finally:
        kernel32.CloseHandle(handle)


def suspend_process(pid: int) -> None:
    if os.name == "nt":
        windows_process_operation(pid, resume=False)
    else:
        os.kill(pid, signal.SIGSTOP)


def resume_process(pid: int) -> None:
    if os.name == "nt":
        windows_process_operation(pid, resume=True)
    else:
        os.kill(pid, signal.SIGCONT)


def read_state(state_path: Path, workspace: Path) -> dict[str, Any] | None:
    if not state_path.is_file():
        return None
    try:
        state = json.loads(state_path.read_text(encoding="utf-8-sig"))
        if normalized_path(state["workspacePath"]) != normalized_path(workspace):
            return None
        state["processIds"] = [int(value) for value in state.get("processIds", [])]
        return state
    except (OSError, ValueError, KeyError, TypeError):
        return None


def write_state(state_path: Path, workspace: Path, processes: list[ProcessInfo]) -> None:
    state_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = state_path.with_suffix(".tmp")
    payload = {
        "schemaVersion": 2,
        "workspacePath": str(workspace),
        "processIds": [process.pid for process in processes],
        "stoppedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "platform": sys.platform,
    }
    temporary_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    temporary_path.replace(state_path)


def remove_state(state_path: Path) -> None:
    try:
        state_path.unlink()
    except FileNotFoundError:
        pass


def launch_vscode(workspace: Path) -> None:
    candidates = ("code", "code-insiders", "codium")
    executable = next((shutil.which(name) for name in candidates if shutil.which(name)), None)
    if executable:
        command = [executable, "--reuse-window", str(workspace)]
    elif sys.platform == "darwin" and shutil.which("open"):
        command = ["open", "-a", "Visual Studio Code", str(workspace)]
    else:
        raise RuntimeError(
            "The VS Code command was not found. Open the workspace in VS Code to start JDT LS."
        )
    subprocess.Popen(
        command,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=os.name != "nt",
    )


def format_pids(processes: list[ProcessInfo]) -> str:
    return ", ".join(str(process.pid) for process in processes)


def run(args: argparse.Namespace) -> int:
    workspace = Path(args.workspace).expanduser().resolve()
    if not workspace.is_dir():
        raise RuntimeError(f"Workspace does not exist: {workspace}")
    storage_paths = storage_paths_for_workspace(workspace)
    if not storage_paths:
        raise RuntimeError(
            f"VS Code workspace metadata was not found for: {workspace}. "
            "Open the folder in VS Code once, then retry."
        )
    if args.verbose:
        print("Workspace storage: " + ", ".join(str(path) for path in storage_paths))

    state_path = workspace / STATE_RELATIVE_PATH
    state = read_state(state_path, workspace)
    processes = language_server_processes(storage_paths)
    process_by_pid = {process.pid: process for process in processes}
    paused_ids = set(state["processIds"]) & process_by_pid.keys() if state else set()

    if args.action == "status":
        if not processes:
            print(f"Java language server: STOPPED (workspace: {workspace})")
        elif paused_ids == process_by_pid.keys():
            print(f"Java language server: PAUSED (PID: {format_pids(processes)}, workspace: {workspace})")
        elif paused_ids:
            running = [process for process in processes if process.pid not in paused_ids]
            paused = [process for process in processes if process.pid in paused_ids]
            print(
                "Java language server: MIXED "
                f"(running PID: {format_pids(running)}; paused PID: {format_pids(paused)})"
            )
        else:
            print(f"Java language server: RUNNING (PID: {format_pids(processes)}, workspace: {workspace})")
        return 0

    if args.action == "stop":
        if not processes:
            print(f"Java language server is already stopped for: {workspace}")
            return 0
        to_suspend = [process for process in processes if process.pid not in paused_ids]
        if not to_suspend:
            print(f"Java language server is already paused (PID: {format_pids(processes)}).")
            return 0
        suspended: list[ProcessInfo] = []
        try:
            for process in to_suspend:
                suspend_process(process.pid)
                suspended.append(process)
        except (OSError, RuntimeError):
            for process in suspended:
                try:
                    resume_process(process.pid)
                except OSError:
                    print(f"Warning: rollback could not resume JDT LS PID {process.pid}.", file=sys.stderr)
            raise
        write_state(state_path, workspace, processes)
        print(
            f"Java language server paused (PID: {format_pids(processes)}). "
            "Maven and application Java processes were not changed."
        )
        return 0

    if paused_ids:
        resumed: list[ProcessInfo] = []
        try:
            for pid in sorted(paused_ids):
                resume_process(pid)
                resumed.append(process_by_pid[pid])
        except (OSError, RuntimeError):
            remaining = [process_by_pid[pid] for pid in paused_ids if pid not in {p.pid for p in resumed}]
            if remaining:
                write_state(state_path, workspace, remaining)
            raise
        remove_state(state_path)
        print(f"Java language server resumed (PID: {format_pids(resumed)}).")
        return 0
    if processes:
        remove_state(state_path)
        print(f"Java language server is already running (PID: {format_pids(processes)}).")
        return 0

    remove_state(state_path)
    launch_vscode(workspace)
    deadline = time.monotonic() + args.start_wait_seconds
    while time.monotonic() < deadline:
        time.sleep(0.5)
        processes = language_server_processes(storage_paths)
        if processes:
            print(f"Java language server started (PID: {format_pids(processes)}).")
            return 0
    raise RuntimeError(
        f"VS Code was opened, but JDT LS did not start within {args.start_wait_seconds} seconds. "
        "Check that the redhat.java extension is enabled."
    )


def main() -> int:
    try:
        return run(parse_args())
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"Java language server control failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
