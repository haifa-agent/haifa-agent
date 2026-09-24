#!/usr/bin/env python3
"""Cross-platform Personal Assistant real-environment lifecycle."""

from __future__ import annotations

import argparse
import os
import shutil
import socket
import stat
import subprocess
import sys
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Mapping, NoReturn, Sequence

FRONTEND_PORT = 20000
BACKEND_PORT = 20001
DEFAULT_STARTUP_TIMEOUT_SECONDS = 180
# Loopback health checks must never travel through a configured HTTP proxy.
HEALTH_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

@dataclass(frozen=True)
class Paths:
    repository: Path
    server: Path
    web: Path
    runtime: Path
    data: Path
    logs: Path
    backend: Path
    maven_wrapper: Path

def fail(message: str) -> NoReturn:
    raise RuntimeError(message)

def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description="Start or reuse the real Personal Assistant environment.")
    result.add_argument("--rebuild", action="store_true", help="Clean package the backend and rebuild the frontend.")
    result.add_argument("--backend-jar", default="", help="Use an already built server JAR instead of target/.")
    result.add_argument("--startup-timeout-seconds", type=int, default=DEFAULT_STARTUP_TIMEOUT_SECONDS)
    return result

def validate_arguments(args: argparse.Namespace) -> None:
    if not 30 <= args.startup_timeout_seconds <= 600:
        fail("--startup-timeout-seconds must be from 30 to 600.")
    if args.rebuild and args.backend_jar:
        fail("--rebuild and --backend-jar cannot be used together.")

def rebuild_port_conflict_message() -> str:
    return ("Rebuild requires ports 20000 and 20001 to be free; stop the current listeners, "
            "then rerun this launcher with --rebuild.")

def paths() -> Paths:
    root = Path(__file__).resolve().parents[1]
    applications = root / "haifa-agent-applications"
    runtime = root / "local-tmp/personal-assistant-real"
    return Paths(root, applications / "haifa-agent-personal-assistant-server",
                 applications / "haifa-agent-personal-assistant-web", runtime, runtime / "data",
                 runtime / "logs", runtime / "backend", root / ("mvnw.cmd" if os.name == "nt" else "mvnw"))

def command_line(executable: str | Path, *arguments: str) -> list[str]:
    value = str(executable)
    if os.name == "nt" and Path(value).suffix.lower() in {".cmd", ".bat"}:
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/s", "/c", value, *arguments]
    return [value, *arguments]

def required_command(name: str) -> str:
    value = shutil.which(name) or (shutil.which(f"{name}.cmd") if os.name == "nt" else None)
    if value is None:
        fail(f"Required command '{name}' was not found on PATH.")
    return value

def run_checked(executable: str | Path, *arguments: str, cwd: Path | None = None,
                environment: Mapping[str, str] | None = None) -> None:
    completed = subprocess.run(command_line(executable, *arguments), cwd=cwd,
                               env=None if environment is None else dict(environment), check=False)
    if completed.returncode != 0:
        fail(f"Command '{Path(str(executable)).name}' failed with exit code {completed.returncode}.")

def http_healthy(uri: str) -> bool:
    try:
        with HEALTH_OPENER.open(uri, timeout=3) as response:
            return 200 <= response.status < 400
    except (OSError, ValueError):
        return False

def port_open(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.5):
            return True
    except OSError:
        return False

def start_process(executable: str | Path, arguments: Sequence[str], cwd: Path, environment: Mapping[str, str],
                  stdout_path: Path, stderr_path: Path) -> subprocess.Popen[bytes]:
    child_environment = os.environ.copy()
    child_environment.update(environment)
    flags = (subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW) if os.name == "nt" else 0
    with stdout_path.open("ab") as stdout, stderr_path.open("ab") as stderr:
        return subprocess.Popen(command_line(executable, *arguments), cwd=cwd, env=child_environment,
                                stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr, creationflags=flags,
                                start_new_session=os.name != "nt")

def wait_for_http(role: str, uri: str, process: subprocess.Popen[bytes], timeout_seconds: int,
                  stdout_path: Path, stderr_path: Path) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if process.poll() is not None:
            fail(f"{role} exited with code {process.returncode}. Logs: {stdout_path} ; {stderr_path}")
        if http_healthy(uri):
            return
        time.sleep(0.5)
    fail(f"{role} did not become healthy within {timeout_seconds} seconds. Logs: {stdout_path} ; {stderr_path}")

def terminate_process(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()

def backend_build_arguments(rebuild: bool) -> tuple[str, ...]:
    goals = ("clean", "package") if rebuild else ("package",)
    return ("-pl", ":haifa-agent-personal-assistant-server", "-am", "-DskipUnitTests=true", *goals)

def latest_server_jar(value: Paths) -> Path | None:
    candidates = [c for c in (value.server / "target").glob("haifa-agent-personal-assistant-server-*.jar")
                  if not c.name.endswith(("-sources.jar", "-javadoc.jar"))]
    return max(candidates, key=lambda candidate: candidate.stat().st_mtime, default=None)

def build_backend(value: Paths, rebuild: bool) -> None:
    if not value.maven_wrapper.is_file():
        fail(f"Maven wrapper was not found: {value.maven_wrapper}")
    print(f"{'Rebuilding' if rebuild else 'Building'} the Personal Assistant backend...", flush=True)
    run_checked(value.maven_wrapper, *backend_build_arguments(rebuild), cwd=value.repository)

def resolve_backend_jar(args: argparse.Namespace, value: Paths) -> Path:
    if args.backend_jar:
        candidate = Path(args.backend_jar).expanduser()
        if not candidate.is_file():
            fail(f"--backend-jar is not a readable file: {candidate}")
        return candidate
    if args.rebuild or latest_server_jar(value) is None:
        build_backend(value, args.rebuild)
    candidate = latest_server_jar(value)
    if candidate is None:
        fail("Backend build did not produce a readable Personal Assistant Server JAR.")
    return candidate

def stage_backend_jar(source: Path, value: Paths) -> Path:
    value.backend.mkdir(parents=True, exist_ok=True)
    staged = value.backend / "app.jar"
    shutil.copyfile(source, staged)
    return staged

def frontend_dependencies_installed(value: Paths) -> bool:
    return (value.web / "node_modules/serve/build/main.js").is_file()

def frontend_dist_ready(value: Paths) -> bool:
    return (value.web / "dist/index.html").is_file()

def ensure_frontend(value: Paths, rebuild: bool, npm: str) -> None:
    if not frontend_dependencies_installed(value):
        print("Installing locked frontend dependencies...", flush=True)
        run_checked(npm, "ci", cwd=value.web)
    if rebuild or not frontend_dist_ready(value):
        print("Building the standalone Personal Assistant frontend...", flush=True)
        environment = os.environ.copy()
        environment["VITE_PERSONAL_ASSISTANT_API_BASE_URL"] = f"http://127.0.0.1:{BACKEND_PORT}/api/v1"
        run_checked(npm, "run", "build", cwd=value.web, environment=environment)

def runtime_environment(value: Paths) -> dict[str, str]:
    """Inject only the local runtime directory; provider, model and credential facts live in application.yml."""
    return {"HAIFA_PERSONAL_DATA_DIR": str(value.data)}

def start_or_reuse(children: list[subprocess.Popen[bytes]], role: str, port: int, health_uri: str,
                   work_directory: Path, environment: Mapping[str, str], timeout_seconds: int, value: Paths,
                   launch: Callable[[], tuple[str, tuple[str, ...]]]) -> str:
    if http_healthy(health_uri):
        return "reused"
    if port_open(port):
        fail(f"Port {port} is occupied, but {role} health check failed. No process was stopped.")
    command, arguments = launch()
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    stdout_path = value.logs / f"{role}-{timestamp}.out.log"
    stderr_path = value.logs / f"{role}-{timestamp}.err.log"
    process = start_process(command, arguments, work_directory, environment, stdout_path, stderr_path)
    children.append(process)
    wait_for_http(role, health_uri, process, timeout_seconds, stdout_path, stderr_path)
    return "started"

def supervise(children: Sequence[subprocess.Popen[bytes]]) -> None:
    """Foreground launcher: stay alive while it owns children so Ctrl+C can unwind and stop them."""
    while children:
        for process in children:
            if process.poll() is not None:
                fail(f"A service started by this script exited with code {process.returncode}.")
        time.sleep(0.5)

def start_environment(args: argparse.Namespace, value: Paths) -> None:
    if args.rebuild and any(port_open(port) for port in (FRONTEND_PORT, BACKEND_PORT)):
        fail(rebuild_port_conflict_message())
    java, node, npm = required_command("java"), required_command("node"), required_command("npm")
    for directory in (value.runtime, value.data, value.logs, value.backend):
        directory.mkdir(parents=True, exist_ok=True)
        directory.chmod(stat.S_IRWXU)
    backend_health_uri = f"http://127.0.0.1:{BACKEND_PORT}/actuator/health"
    frontend_health_uri = f"http://127.0.0.1:{FRONTEND_PORT}/"
    children: list[subprocess.Popen[bytes]] = []
    statuses: list[tuple[str, str]] = []
    try:
        statuses.append(("personal-backend", start_or_reuse(
            children, "personal-backend", BACKEND_PORT, backend_health_uri, value.backend, runtime_environment(value),
            args.startup_timeout_seconds, value,
            lambda: (java, ("-jar", str(stage_backend_jar(resolve_backend_jar(args, value), value)))))))
        ensure_frontend(value, args.rebuild, npm)
        statuses.append(("personal-web", start_or_reuse(
            children, "personal-web", FRONTEND_PORT, frontend_health_uri, value.web, {},
            args.startup_timeout_seconds, value,
            lambda: (node, (str(value.web / "node_modules/serve/build/main.js"), "-s", str(value.web / "dist"),
                            "-l", f"tcp://127.0.0.1:{FRONTEND_PORT}", "--no-clipboard")))))
        print("\n".join(["", "Real Personal Assistant environment is ready."]
                        + [f"  {role:<18} {status}" for role, status in statuses]
                        + ["", f"  Personal Web:    http://127.0.0.1:{FRONTEND_PORT}/",
                           f"  Personal API:    http://127.0.0.1:{BACKEND_PORT}/api/v1",
                           f"  Backend health:  {backend_health_uri}",
                           f"  Runtime data:    {value.data}",
                           f"  Runtime logs:    {value.logs}",
                           "Credentials and the default model come from application.yml and the PA model panel.",
                           "Press Ctrl+C to stop the services started by this script."]), flush=True)
        supervise(children)
    finally:
        for process in children:
            terminate_process(process)

def main(arguments: Iterable[str] | None = None) -> int:
    try:
        args = parser().parse_args(arguments)
        validate_arguments(args)
        start_environment(args, paths())
        return 0
    except KeyboardInterrupt:
        print("Interrupted; the services started by this script were stopped.", file=sys.stderr)
        return 0
    except (OSError, RuntimeError, ValueError) as exception:
        print(f"Error: {exception}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
