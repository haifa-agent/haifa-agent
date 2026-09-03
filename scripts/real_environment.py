#!/usr/bin/env python3
"""Cross-platform Personal Assistant real-environment lifecycle."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import secrets
import shutil
import signal
import socket
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence

FRONTEND_PORT = 20000
BACKEND_PORT = 20001
MCP_PORT = 20002
DEFAULT_MODEL_ID = "deepseek-chat-flash"
EXPECTED_SERVER_START_CLASS = (
    "io.haifa.agent.personalassistant.server.PersonalAssistantServerApplication"
)
BACKEND_LAUNCH_MODES = ("jar", "classpath")
DEVELOPMENT_CLASSPATH_ENVIRONMENT = "HAIFA_PERSONAL_DEV_CLASSPATH"
BAILIAN_DEFAULT_MODEL_ID = "qwen3.7-max-2026-05-17"
ANTIGRAVITY_DIRECT_MODEL_ID = "antigravity-gemini"
SILICONFLOW_MODEL_IDS = (
    "siliconflow-deepseek-v4-pro",
    "siliconflow-deepseek-v4-flash",
    "siliconflow-qwen3-vl-32b",
    "siliconflow-qwen3-32b",
    "siliconflow-kimi-k3",
    "siliconflow-kimi-k2-6",
    "siliconflow-glm-5-2",
    "siliconflow-glm-5-1",
)
SILICONFLOW_MODEL_ID = "siliconflow-deepseek-v4-flash"
CODEX_MODEL_IDS = ("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")
SUPPORTED_DEFAULT_MODEL_IDS = (
    "deepseek-chat-pro",
    "deepseek-chat-flash",
    "deepseek-responses-flash",
    "deepseek-anthropic-flash",
    "deepseek-responses-pro",
    "deepseek-anthropic-pro",
    BAILIAN_DEFAULT_MODEL_ID,
    "qwen3.7-plus",
    "qwen3.7-flash",
    "qwen3-vl-plus",
    "qwen3.7-max-responses",
    "qwen3.7-plus-responses",
    "qwen3.8-max-0902",
    "qwen3.8-max",
    "qwen3.8-flash",
    "kimi-k3",
    "kimi-k2.7-code",
    "kimi-k2.6",
    "glm-5.2-chat",
    "glm-5.2-anthropic",
    "glm-5.1-chat",
    "glm-5-chat",
    *CODEX_MODEL_IDS,
    *SILICONFLOW_MODEL_IDS,
    ANTIGRAVITY_DIRECT_MODEL_ID,
)
ALLOWED_MCP_TOOLS = ",".join(
    (
        "location_search",
        "weather_current",
        "weather_forecast",
        "air_quality",
        "time_now",
        "time_convert",
        "currency_rate",
        "currency_convert",
        "holiday_list",
        "holiday_next",
        "workday_is_workday",
        "workday_add",
        "calculate",
        "unit_convert",
        "wikipedia_search",
        "wikipedia_summary",
        "microsoft_docs_search",
        "microsoft_docs_fetch",
        "microsoft_code_sample_search",
    )
)


@dataclass(frozen=True)
class Paths:
    repository: Path
    server: Path
    web: Path
    runtime: Path
    data: Path
    logs: Path
    state: Path
    stop_state: Path
    maven_wrapper: Path


@dataclass(frozen=True)
class ServiceDefinition:
    role: str
    port: int
    process_name: str
    command_tokens: tuple[str, ...]


@dataclass(frozen=True)
class ServiceRecord:
    Role: str
    Status: str
    Pid: int | None
    Url: str
    WorkDirectory: str
    Stdout: str | None
    Stderr: str | None


@dataclass(frozen=True)
class AntigravityConfiguration:
    endpoint: str
    provider_model_id: str
    proxy: str


@dataclass(frozen=True)
class BackendLaunch:
    command: str
    arguments: tuple[str, ...]
    environment: dict[str, str]


def warn(message: str) -> None:
    print(f"Warning: {message}", file=sys.stderr)


def fail(message: str) -> "NoReturn":
    raise RuntimeError(message)


def rebuild_port_conflict_message() -> str:
    return (
        "Rebuild requires ports 20000, 20001, and 20002 to be free.\n"
        "Stop the running environment first, then rebuild:\n"
        "  PowerShell: .\\scripts\\start-real-environment.ps1 --stop\n"
        "              .\\scripts\\start-real-environment.ps1 --rebuild\n"
        "  Bash:       ./scripts/start-real-environment.sh --stop\n"
        "              ./scripts/start-real-environment.sh --rebuild"
    )


def parser() -> argparse.ArgumentParser:
    windows = os.name == "nt"
    home = Path.home()
    workspace = Path("D:/workspace") if windows else home / "workspace"
    agents = Path("D:/agents") if windows else home / "agents"
    software = Path("D:/dev/software") if windows else home / "dev/software"
    result = argparse.ArgumentParser(
        description="Start, reuse, validate, or stop the real Personal Assistant environment."
    )
    result.add_argument(
        "--deepseek-key-file",
        default=os.getenv("HAIFA_DEEPSEEK_KEY_FILE", str(workspace / "ss-deepseek.txt")),
    )
    result.add_argument(
        "--default-model-id",
        choices=SUPPORTED_DEFAULT_MODEL_IDS,
        default=os.getenv("HAIFA_PERSONAL_DEFAULT_MODEL_ID", "").strip() or None,
    )
    result.add_argument(
        "--bailian-key-file",
        default=os.getenv("HAIFA_BAILIAN_KEY_FILE", str(workspace / "ss-bailian.txt")),
    )
    result.add_argument(
        "--bailian-region",
        default=os.getenv("ALIYUN_BAILIAN_REGION", "cn-beijing"),
    )
    result.add_argument(
        "--kimi-key-file",
        default=os.getenv("HAIFA_KIMI_KEY_FILE", str(workspace / "ss-kimi.txt")),
    )
    result.add_argument(
        "--bigmodel-key-file",
        default=os.getenv("HAIFA_BIGMODEL_KEY_FILE", str(workspace / "ss-bigmodel.txt")),
    )
    result.add_argument(
        "--siliconflow-key-file",
        default=os.getenv("HAIFA_SILICONFLOW_KEY_FILE", str(workspace / "ss-siliconflow.txt")),
    )
    result.add_argument(
        "--aliyun-iqs-key-file",
        default=os.getenv("HAIFA_ALIYUN_IQS_KEY_FILE", str(workspace / "ss-aliyun-iqs.txt")),
    )
    result.add_argument(
        "--browserless-key-file",
        default=os.getenv("HAIFA_BROWSERLESS_KEY_FILE", str(workspace / "ss-browserless.txt")),
    )
    result.add_argument(
        "--tavily-key-file",
        default=os.getenv("HAIFA_TAVILY_KEY_FILE", str(workspace / "ss-tavily.txt")),
    )
    result.add_argument(
        "--web-search-provider",
        choices=("aliyun", "tavily"),
        default=os.getenv("HAIFA_PERSONAL_WEB_SEARCH_PROVIDER", "tavily"),
    )
    result.add_argument(
        "--web-fetch-provider",
        choices=("aliyun", "browserless", "tavily"),
        default=os.getenv("HAIFA_PERSONAL_WEB_FETCH_PROVIDER", "tavily"),
    )
    result.add_argument(
        "--continuation-key-file",
        default=os.getenv(
            "HAIFA_PERSONAL_CONTINUATION_KEY_FILE",
            str(workspace / "ss-haifa-personal-continuation.txt"),
        ),
    )
    result.add_argument(
        "--utility-mcp-directory",
        default=os.getenv(
            "HAIFA_UTILITY_MCP_DIRECTORY",
            str(workspace / "haifa/haifa-ai/haifa-ai-utility-mcp-server"),
        ),
    )
    result.add_argument(
        "--utility-mcp-proxy-url",
        default=os.getenv("HAIFA_UTILITY_MCP_PROXY_URL", "http://127.0.0.1:2081"),
    )
    result.add_argument(
        "--utility-mcp-proxy-providers",
        default=os.getenv("HAIFA_UTILITY_MCP_PROXY_PROVIDERS", "wikimedia"),
    )
    result.add_argument(
        "--personal-skill-root",
        default=os.getenv(
            "HAIFA_PERSONAL_SKILL_ROOT",
            str(agents / "hermes-agent/optional-skills/finance"),
        ),
    )
    result.add_argument(
        "--trusted-script-manifest",
        default=os.getenv("HAIFA_PERSONAL_TRUSTED_SCRIPT_MANIFEST", ""),
    )
    result.add_argument("--startup-timeout-seconds", type=int, default=180)
    result.add_argument(
        "--backend-launch-mode",
        choices=BACKEND_LAUNCH_MODES,
        default=os.getenv("HAIFA_PERSONAL_BACKEND_LAUNCH_MODE", "jar").strip() or "jar",
        help="Launch the backend from an executable JAR or an IDE-provided compiled classpath.",
    )
    result.add_argument("--rebuild", action="store_true")
    result.add_argument("--stop", action="store_true")
    result.add_argument("--force", action="store_true")
    result.add_argument("--dry-run", action="store_true")
    return result


def validate_arguments(args: argparse.Namespace) -> None:
    if not 30 <= args.startup_timeout_seconds <= 600:
        fail("--startup-timeout-seconds must be from 30 to 600.")
    if args.stop and args.rebuild:
        fail("--stop and --rebuild cannot be used together.")
    if args.force and not args.stop:
        fail("--force may only be used with --stop.")
    if args.dry_run and not args.stop:
        fail("--dry-run may only be used with --stop.")
    if not args.stop and args.backend_launch_mode == "classpath" and args.rebuild:
        fail("--backend-launch-mode classpath does not support --rebuild.")


def paths() -> Paths:
    repository = Path(__file__).resolve().parents[1]
    server = repository / "haifa-agent-applications/haifa-agent-personal-assistant-server"
    web = repository / "haifa-agent-applications/haifa-agent-personal-assistant-web"
    runtime = repository / "local-tmp/personal-assistant-real"
    return Paths(
        repository=repository,
        server=server,
        web=web,
        runtime=runtime,
        data=runtime / "data",
        logs=runtime / "logs",
        state=runtime / "last-start.json",
        stop_state=runtime / "last-stop.json",
        maven_wrapper=repository / ("mvnw.cmd" if os.name == "nt" else "mvnw"),
    )


def required_command(name: str) -> str:
    value = shutil.which(name)
    if value is None and os.name == "nt":
        value = shutil.which(f"{name}.cmd")
    if value is None:
        fail(f"Required command '{name}' was not found on PATH.")
    return value


def executable_command(executable: str | Path, *arguments: str) -> list[str]:
    value = str(executable)
    if os.name == "nt" and Path(value).suffix.lower() in {".cmd", ".bat"}:
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/s", "/c", value, *arguments]
    return [value, *arguments]


def run_checked(
    executable: str | Path,
    *arguments: str,
    cwd: Path | None = None,
    environment: Mapping[str, str] | None = None,
) -> None:
    completed = subprocess.run(
        executable_command(executable, *arguments),
        cwd=cwd,
        env=None if environment is None else dict(environment),
        check=False,
    )
    if completed.returncode != 0:
        fail(f"Command '{Path(str(executable)).name}' failed with exit code {completed.returncode}.")


def http_healthy(uri: str) -> bool:
    try:
        with urllib.request.urlopen(uri, timeout=3) as response:
            return 200 <= response.status < 400
    except (OSError, urllib.error.URLError, ValueError):
        return False


def port_open(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.5):
            return True
    except OSError:
        return False


def listening_process_ids(port: int) -> list[int]:
    if os.name == "nt":
        completed = subprocess.run(
            ["netstat.exe", "-ano", "-p", "tcp"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        pattern = re.compile(rf"^\s*TCP\s+\S+:{port}\s+\S+\s+LISTENING\s+(\d+)\s*$", re.I)
        return sorted({int(match.group(1)) for line in completed.stdout.splitlines() if (match := pattern.match(line))})
    lsof = required_command("lsof")
    completed = subprocess.run(
        [lsof, "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-t"],
        capture_output=True,
        text=True,
        check=False,
    )
    return sorted({int(line) for line in completed.stdout.splitlines() if line.strip().isdigit()})


def listening_process_id(port: int) -> int | None:
    values = listening_process_ids(port)
    if len(values) > 1:
        fail(f"Port {port} has multiple listening processes; refusing to guess an owner.")
    return values[0] if values else None


def start_process(
    executable: str | Path,
    arguments: Sequence[str],
    cwd: Path,
    environment: Mapping[str, str],
    stdout_path: Path,
    stderr_path: Path,
) -> subprocess.Popen[bytes]:
    child_environment = os.environ.copy()
    child_environment.update(environment)
    flags = 0
    start_new_session = os.name != "nt"
    if os.name == "nt":
        flags = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW
    with stdout_path.open("ab") as stdout, stderr_path.open("ab") as stderr:
        return subprocess.Popen(
            executable_command(executable, *arguments),
            cwd=cwd,
            env=child_environment,
            stdin=subprocess.DEVNULL,
            stdout=stdout,
            stderr=stderr,
            creationflags=flags,
            start_new_session=start_new_session,
        )


def wait_for_http(
    name: str,
    uri: str,
    process: subprocess.Popen[bytes],
    timeout_seconds: int,
    stdout_path: Path,
    stderr_path: Path,
) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        result = process.poll()
        if result is not None:
            fail(f"{name} exited with code {result}. Logs: {stdout_path} ; {stderr_path}")
        if http_healthy(uri):
            return
        time.sleep(0.5)
    fail(f"{name} did not become healthy within {timeout_seconds} seconds. Logs: {stdout_path} ; {stderr_path}")


def read_secret_file(value: str, label: str) -> str:
    path = Path(value).expanduser()
    if not path.is_file():
        fail(f"{label} key file was not found: {path}")
    secret = path.read_text(encoding="utf-8").strip()
    if not secret:
        fail(f"{label} key file is empty: {path}")
    return secret


def optional_secret_file(value: str, label: str, environment_name: str) -> str | None:
    configured = environment_value(environment_name)
    if configured:
        return configured
    path = Path(value).expanduser()
    if not path.exists():
        return None
    return read_secret_file(value, label)


def read_key_value_file(path: Path, label: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if ":" not in line:
            fail(f"{label} key file line {line_number} must use KEY:VALUE format.")
        name, value = (part.strip() for part in line.split(":", 1))
        if name not in {"API_KEY", "WORKSPACE_ID", "REGION"} or not value:
            fail(f"{label} key file line {line_number} is invalid.")
        if name in values:
            fail(f"{label} key file contains duplicate {name}.")
        values[name] = value
    return values


def environment_value(name: str) -> str:
    value = os.getenv(name, "").strip()
    if value:
        return value
    if os.name == "nt":
        try:
            import winreg

            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as key:
                value = str(winreg.QueryValueEx(key, name)[0]).strip()
        except (FileNotFoundError, OSError):
            value = ""
    return value


def optional_openai_environment(environment: Mapping[str, str] | None = None) -> tuple[str, str, str] | None:
    names = ("OPENAI_BASE_URL", "OPENAI_API_KEY", "OPENAI_MODEL_ID")
    values = {
        name: (environment_value(name) if environment is None else environment.get(name, "").strip())
        for name in names
    }
    missing = [name for name in names if not values[name]]
    if missing:
        configured = [name for name in names if values[name]]
        if configured:
            warn(
                "Ignoring incomplete optional OpenAI provider configuration. "
                f"Configured: {', '.join(configured)}; missing: {', '.join(missing)}."
            )
        return None
    return values["OPENAI_BASE_URL"], values["OPENAI_API_KEY"], values["OPENAI_MODEL_ID"]


def antigravity_configuration(
    environment: Mapping[str, str] | None = None,
) -> AntigravityConfiguration:
    source = os.environ if environment is None else environment
    return AntigravityConfiguration(
        source.get(
            "HAIFA_ANTIGRAVITY_MODEL_ENDPOINT",
            "https://daily-cloudcode-pa.googleapis.com/v1internal",
        ).strip()
        or "https://daily-cloudcode-pa.googleapis.com/v1internal",
        source.get("HAIFA_ANTIGRAVITY_MODEL", "gemini-3-flash").strip() or "gemini-3-flash",
        source.get("HAIFA_ANTIGRAVITY_PROXY_URL", "http://127.0.0.1:2081").strip()
        or "http://127.0.0.1:2081",
    )


def optional_bailian_configuration(
    key_file: str,
    default_region: str = "cn-beijing",
    environment: Mapping[str, str] | None = None,
) -> tuple[str, str, str] | None:
    source = {
        name: (environment_value(name) if environment is None else environment.get(name, "").strip())
        for name in ("DASHSCOPE_API_KEY", "ALIYUN_BAILIAN_WORKSPACE_ID", "ALIYUN_BAILIAN_REGION")
    }
    path = Path(key_file).expanduser()
    file_values: dict[str, str] = {}
    if path.is_file():
        file_values = read_key_value_file(path, "Bailian")
    source["DASHSCOPE_API_KEY"] = source["DASHSCOPE_API_KEY"] or file_values.get("API_KEY", "")
    source["ALIYUN_BAILIAN_WORKSPACE_ID"] = (
        source["ALIYUN_BAILIAN_WORKSPACE_ID"] or file_values.get("WORKSPACE_ID", "")
    )
    if not source["DASHSCOPE_API_KEY"] and not source["ALIYUN_BAILIAN_WORKSPACE_ID"]:
        return None
    source["ALIYUN_BAILIAN_REGION"] = (
        source["ALIYUN_BAILIAN_REGION"] or file_values.get("REGION", "") or default_region.strip()
    )
    configured = [name for name, value in source.items() if value]
    if not configured:
        return None
    missing = [name for name, value in source.items() if not value]
    if missing:
        warn(
            "Ignoring incomplete optional Bailian provider configuration. "
            f"Configured: {', '.join(configured)}; missing: {', '.join(missing)}."
        )
        return None
    dns_label = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
    workspace_id = source["ALIYUN_BAILIAN_WORKSPACE_ID"].lower()
    region = source["ALIYUN_BAILIAN_REGION"].lower()
    if not dns_label.fullmatch(workspace_id) or not dns_label.fullmatch(region):
        fail("Bailian workspace and region must be valid DNS labels.")
    return (
        source["DASHSCOPE_API_KEY"],
        workspace_id,
        region,
    )


def restrict_secret_file(path: Path) -> None:
    try:
        path.chmod(stat.S_IRUSR | stat.S_IWUSR)
        if os.name == "nt":
            identity = "\\".join(
                part for part in (os.getenv("USERDOMAIN", ""), os.getenv("USERNAME", "")) if part
            )
            if identity:
                subprocess.run(
                    ["icacls.exe", str(path), "/inheritance:r", "/grant:r", f"{identity}:(F)"],
                    capture_output=True,
                    check=True,
                )
    except (OSError, subprocess.SubprocessError) as exception:
        warn(f"Continuation key was created, but permissions could not be restricted: {exception}")


def continuation_key(value: str) -> str:
    path = Path(value).expanduser()
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        encoded = base64.b64encode(secrets.token_bytes(32)).decode("ascii")
        path.write_text(encoded, encoding="ascii")
        restrict_secret_file(path)
        print(f"Created a persistent continuation key file: {path}")
    result = path.read_text(encoding="ascii").strip()
    try:
        decoded = base64.b64decode(result, validate=True)
    except ValueError as exception:
        raise RuntimeError(f"Continuation key file does not contain valid Base64: {path}") from exception
    if len(decoded) != 32:
        fail(f"Continuation key must decode to exactly 32 bytes: {path}")
    return result


def atomic_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f"{path.name}.tmp-{os.getpid()}")
    temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    temporary.chmod(stat.S_IRUSR | stat.S_IWUSR)
    temporary.replace(path)


def process_information(process_id: int) -> tuple[str, str]:
    if os.name == "nt":
        powershell = required_command("powershell")
        script = (
            f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId = {process_id}' -ErrorAction Stop;"
            "if($null -eq $p){exit 3};"
            "[pscustomobject]@{Name=$p.Name;CommandLine=$p.CommandLine}|ConvertTo-Json -Compress"
        )
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        if completed.returncode != 0:
            fail(f"Cannot inspect PID {process_id}: {completed.stderr.strip() or 'process unavailable'}")
        value = json.loads(completed.stdout)
        return str(value.get("Name") or ""), str(value.get("CommandLine") or "")
    completed = subprocess.run(
        ["ps", "-p", str(process_id), "-o", "comm=", "-o", "command="],
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0 or not completed.stdout.strip():
        fail(f"PID {process_id} no longer exists.")
    line = completed.stdout.strip().splitlines()[0].strip()
    name, _, command = line.partition(" ")
    return Path(name).name, command


def validate_process(definition: ServiceDefinition, process_id: int) -> None:
    name, command = process_information(process_id)
    if name.lower() != definition.process_name.lower():
        fail(
            f"{definition.role} PID {process_id} is '{name}', expected "
            f"'{definition.process_name}'. No process was stopped."
        )
    if not any(token.lower() in command.lower() for token in definition.command_tokens):
        fail(
            f"{definition.role} PID {process_id} command line does not contain any expected token "
            f"{definition.command_tokens}. No process was stopped."
        )


def stop_process(process_id: int, force: bool) -> None:
    if os.name == "nt":
        powershell = required_command("powershell")
        command = [
            powershell,
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            f"Stop-Process -Id {process_id}{' -Force' if force else ''} -ErrorAction Stop",
        ]
        completed = subprocess.run(command, capture_output=True, check=False)
        if completed.returncode != 0:
            fail(f"Could not stop PID {process_id}; Stop-Process exited with code {completed.returncode}.")
    else:
        os.kill(process_id, signal.SIGTERM)


def wait_for_port_release(port: int, timeout_seconds: int = 30) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if not port_open(port):
            return
        time.sleep(0.25)
    fail(f"Port {port} was not released within {timeout_seconds} seconds.")


def definitions(value: Paths) -> tuple[ServiceDefinition, ...]:
    return (
        ServiceDefinition(
            "personal-web",
            FRONTEND_PORT,
            "node.exe" if os.name == "nt" else "node",
            (str(value.web),),
        ),
        ServiceDefinition(
            "personal-backend",
            BACKEND_PORT,
            "java.exe" if os.name == "nt" else "java",
            (str(value.runtime / "backend"), str(value.server), EXPECTED_SERVER_START_CLASS),
        ),
        ServiceDefinition(
            "utility-mcp",
            MCP_PORT,
            "java.exe" if os.name == "nt" else "java",
            ("org.wrj.haifa.ai.utilitymcp.UtilityMcpServerApplication",),
        ),
    )


def stop_environment(args: argparse.Namespace, value: Paths) -> None:
    records: list[dict[str, object]] = []
    if value.state.is_file():
        try:
            records = json.loads(value.state.read_text(encoding="utf-8-sig"))
        except (OSError, json.JSONDecodeError) as exception:
            if not args.force:
                raise RuntimeError(f"Startup state file could not be read: {value.state}") from exception
            warn(f"Startup state file could not be read; force stop will use listeners: {value.state}")
    elif args.force:
        warn(f"Startup state file was not found; force stop will use listeners: {value.state}")
    else:
        fail(f"Startup state file was not found: {value.state}. No process was stopped.")

    recorded = {str(record.get("Role")): record.get("Pid") for record in records}
    targets: list[tuple[ServiceDefinition, int]] = []
    results: list[dict[str, object]] = []
    for definition in definitions(value):
        current = listening_process_id(definition.port)
        if current is None:
            results.append(
                {"Role": definition.role, "Status": "already-stopped", "Pid": None, "Port": definition.port}
            )
            continue
        expected = recorded.get(definition.role)
        if expected is None and not args.force:
            fail(
                f"No recorded PID exists for {definition.role}, but port {definition.port} is listening. "
                "No process was stopped."
            )
        if expected is not None and int(expected) != current and not args.force:
            fail(
                f"{definition.role} port {definition.port} belongs to PID {current}, but state records "
                f"PID {expected}. No process was stopped."
            )
        try:
            validate_process(definition, current)
        except RuntimeError as exception:
            if not args.force:
                raise
            warn(f"{exception} Force stop will target current listener PID {current}.")
        targets.append((definition, current))

    for definition, process_id in targets:
        if args.dry_run:
            print(f"Would stop {definition.role} PID {process_id} on port {definition.port}.")
            status = "validated"
        else:
            stop_process(process_id, args.force)
            wait_for_port_release(definition.port)
            status = "stopped"
        results.append({"Role": definition.role, "Status": status, "Pid": process_id, "Port": definition.port})

    print("\nPersonal Assistant stop validation completed.")
    for result in results:
        print(
            f"  {result['Role']:<18} {result['Status']:<16} "
            f"PID={result['Pid'] or '-':<8} port={result['Port']}"
        )
    if args.dry_run:
        print("Dry run was enabled; no process was stopped.")
    else:
        atomic_json(value.stop_state, results)
        print(f"Stop state: {value.stop_state}")


def latest_server_jar(value: Paths) -> Path | None:
    candidates = [
        candidate
        for candidate in (value.server / "target").glob("haifa-agent-personal-assistant-server-*.jar")
        if not candidate.name.endswith(("-sources.jar", "-javadoc.jar"))
    ]
    return max(candidates, key=lambda candidate: candidate.stat().st_mtime, default=None)


def manifest_attributes(payload: bytes) -> dict[str, str]:
    unfolded: list[str] = []
    for line in payload.decode("utf-8", "replace").splitlines():
        if line.startswith(" ") and unfolded:
            unfolded[-1] += line[1:]
        else:
            unfolded.append(line)
    return {
        name.strip(): content.strip()
        for line in unfolded
        if ":" in line
        for name, content in (line.split(":", 1),)
    }


def server_jar_validation_error(path: Path | None) -> str | None:
    if path is None or not path.is_file():
        return "executable JAR does not exist"
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            manifest = manifest_attributes(archive.read("META-INF/MANIFEST.MF"))
    except (OSError, KeyError, zipfile.BadZipFile) as exception:
        return f"JAR or manifest cannot be read ({type(exception).__name__})"

    main_class = manifest.get("Main-Class", "")
    if not main_class.startswith("org.springframework.boot.loader.") or not main_class.endswith(
        "JarLauncher"
    ):
        return "manifest Main-Class is not a Spring Boot JarLauncher"
    if manifest.get("Start-Class") != EXPECTED_SERVER_START_CLASS:
        return f"manifest Start-Class is not {EXPECTED_SERVER_START_CLASS}"
    if not any(name.startswith("BOOT-INF/classes/") for name in names):
        return "BOOT-INF/classes is missing"
    if not any(name.startswith("BOOT-INF/lib/") for name in names):
        return "BOOT-INF/lib is missing"
    return None


def ensure_executable_server_jar(value: Paths, rebuild: bool) -> Path:
    server_jar = latest_server_jar(value)
    validation_error = server_jar_validation_error(server_jar)
    if rebuild or validation_error is not None:
        if validation_error is None:
            print("Rebuilding the Personal Assistant backend...")
        else:
            print(f"Building the Personal Assistant backend: {validation_error}.")
        run_checked(
            value.maven_wrapper,
            *backend_build_arguments(rebuild),
            cwd=value.repository,
        )
        server_jar = latest_server_jar(value)
        validation_error = server_jar_validation_error(server_jar)
        if validation_error is not None:
            fail(f"Backend build did not produce an executable Spring Boot JAR: {validation_error}.")
    if server_jar is None:
        fail("Backend build completed without producing an executable server JAR.")
    return server_jar


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def stage_server_jar(source: Path, value: Paths) -> Path:
    validation_error = server_jar_validation_error(source)
    if validation_error is not None:
        fail(f"Refusing to stage a non-executable backend JAR: {validation_error}.")
    deployment = value.runtime / "backend"
    deployment.mkdir(parents=True, exist_ok=True)
    deployment.chmod(stat.S_IRWXU)

    source_digest = file_sha256(source)
    staged = deployment / f"{source.stem}-{source_digest[:16]}.jar"
    if not staged.is_file() or file_sha256(staged) != source_digest:
        temporary = deployment / f"{staged.name}.tmp-{os.getpid()}"
        try:
            shutil.copyfile(source, temporary)
            temporary.chmod(stat.S_IRUSR | stat.S_IWUSR)
            if file_sha256(temporary) != source_digest:
                fail(f"Backend runtime JAR copy verification failed: {temporary}")
            temporary.replace(staged)
        finally:
            temporary.unlink(missing_ok=True)

    for candidate in deployment.glob("haifa-agent-personal-assistant-server-*.jar"):
        if candidate != staged:
            try:
                candidate.unlink()
            except OSError as exception:
                warn(f"Could not remove stale backend runtime JAR {candidate}: {exception}")
    return staged


def backend_environment(
    deepseek_key: str,
    default_model_id: str,
    openai: tuple[str, str, str] | None,
    aliyun_key: str,
    continuation: str,
    value: Paths,
    skill_root: Path,
    trusted_manifest: Path | None,
    bailian: tuple[str, str, str] | None = None,
    kimi_key: str | None = None,
    bigmodel_key: str | None = None,
    browserless_token: str | None = None,
    tavily_key: str | None = None,
    web_search_provider: str = "tavily",
    web_fetch_provider: str = "tavily",
    siliconflow_key: str | None = None,
    antigravity: AntigravityConfiguration | None = None,
) -> dict[str, str]:
    environment = {
        "DEEPSEEK_API_KEY": deepseek_key,
        "HAIFA_PERSONAL_CONTINUATION_KEY": continuation,
        "HAIFA_PERSONAL_DATA_DIR": str(value.data),
        "HAIFA_PERSONAL_DEFAULT_MODEL_ID": default_model_id,
        "HAIFA_PERSONAL_MODELPROVIDERS_0_ID": "deepseek",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_DISPLAYNAME": "DeepSeek",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODE": "remote",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_ALLOWDETERMINISTIC": "false",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_NATIVESTREAMING": "true",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_ENDPOINT": "https://api.deepseek.com",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_CREDENTIALREFERENCE": "env://DEEPSEEK_API_KEY",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_APIBINDINGS_0_STYLE": "openai-chat-completions",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_APIBINDINGS_0_DIALECT": "deepseek-openai-chat",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_APIBINDINGS_1_STYLE": "openai-responses",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_APIBINDINGS_1_DIALECT": "deepseek-openai-responses",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_APIBINDINGS_2_STYLE": "anthropic-messages",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_APIBINDINGS_2_DIALECT": "deepseek-anthropic-messages",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_APIBINDINGS_2_ENDPOINT": "https://api.deepseek.com/anthropic",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_ID": "deepseek-chat-pro",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_DISPLAYNAME": "DeepSeek V4 Pro · Chat Completions",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_MODELDISPLAYNAME": "DeepSeek V4 Pro",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_PROVIDERMODELID": "deepseek-v4-pro",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_STYLE": "openai-chat-completions",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_CAPABILITIES_0": "TEXT_CHAT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_CAPABILITIES_1": "TOOL_CALLING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_CAPABILITIES_2": "STRUCTURED_OUTPUT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_CAPABILITIES_3": "REASONING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_CONTEXTWINDOW": "1048576",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_MAXOUTPUTTOKENS": "393216",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_ID": "deepseek-chat-flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_DISPLAYNAME": "DeepSeek V4 Flash · Chat Completions",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_MODELDISPLAYNAME": "DeepSeek V4 Flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_PROVIDERMODELID": "deepseek-v4-flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_STYLE": "openai-chat-completions",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_CAPABILITIES_0": "TEXT_CHAT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_CAPABILITIES_1": "TOOL_CALLING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_CAPABILITIES_2": "STRUCTURED_OUTPUT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_CAPABILITIES_3": "REASONING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_CONTEXTWINDOW": "1048576",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_MAXOUTPUTTOKENS": "393216",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_ID": "deepseek-responses-flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_DISPLAYNAME": "DeepSeek V4 Flash · Responses",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_MODELDISPLAYNAME": "DeepSeek V4 Flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_PROVIDERMODELID": "deepseek-v4-flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_STYLE": "openai-responses",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_CAPABILITIES_0": "TEXT_CHAT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_CAPABILITIES_1": "TOOL_CALLING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_CAPABILITIES_2": "STRUCTURED_OUTPUT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_CAPABILITIES_3": "REASONING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_CONTEXTWINDOW": "1048576",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_MAXOUTPUTTOKENS": "393216",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_ID": "deepseek-anthropic-flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_DISPLAYNAME": "DeepSeek V4 Flash · Anthropic Messages",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_MODELDISPLAYNAME": "DeepSeek V4 Flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_PROVIDERMODELID": "deepseek-v4-flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_STYLE": "anthropic-messages",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_CAPABILITIES_0": "TEXT_CHAT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_CAPABILITIES_1": "TOOL_CALLING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_CAPABILITIES_2": "REASONING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_CONTEXTWINDOW": "1048576",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_MAXOUTPUTTOKENS": "393216",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_4_ID": "deepseek-responses-pro",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_4_DISPLAYNAME": "DeepSeek V4 Pro · Responses",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_4_MODELDISPLAYNAME": "DeepSeek V4 Pro",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_4_PROVIDERMODELID": "deepseek-v4-pro",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_4_STYLE": "openai-responses",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_4_CAPABILITIES_0": "TEXT_CHAT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_4_CAPABILITIES_1": "TOOL_CALLING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_4_CAPABILITIES_2": "STRUCTURED_OUTPUT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_4_CAPABILITIES_3": "REASONING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_4_CONTEXTWINDOW": "1048576",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_4_MAXOUTPUTTOKENS": "393216",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_5_ID": "deepseek-anthropic-pro",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_5_DISPLAYNAME": "DeepSeek V4 Pro · Anthropic Messages",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_5_MODELDISPLAYNAME": "DeepSeek V4 Pro",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_5_PROVIDERMODELID": "deepseek-v4-pro",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_5_STYLE": "anthropic-messages",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_5_CAPABILITIES_0": "TEXT_CHAT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_5_CAPABILITIES_1": "TOOL_CALLING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_5_CAPABILITIES_2": "REASONING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_5_CONTEXTWINDOW": "1048576",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_5_MAXOUTPUTTOKENS": "393216",
        "HAIFA_PERSONAL_ALLOW_INSECURE_LOOPBACK_MODEL": "true",
        "HAIFA_PERSONAL_SKILL_ROOT": str(skill_root),
        "HAIFA_PERSONAL_TRUSTED_SCRIPT_MANIFEST": str(trusted_manifest or ""),
        "HAIFA_PERSONAL_MCP_MODE": "external",
        "HAIFA_PERSONAL_MCP_ENDPOINT": f"http://127.0.0.1:{MCP_PORT}/mcp",
        "HAIFA_PERSONAL_MCP_ALLOWED_TOOLS": ALLOWED_MCP_TOOLS,
        "HAIFA_PERSONAL_MCP_ALIAS_NAMESPACE": "utility",
        "HAIFA_PERSONAL_MCP_SERVER_ID": "haifa-utility",
        "HAIFA_PERSONAL_MCP_DISPLAY_NAME": "Haifa Utility MCP",
        "HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED": "true",
    }
    web_provider_specs = {
        "aliyun": {
            "secret": aliyun_key,
            "environment": "ALIYUN_IQS_API_KEY",
            "search_endpoint": "https://cloud-iqs.aliyuncs.com/search/unified",
            "fetch_endpoint": "https://cloud-iqs.aliyuncs.com/readpage/basic",
        },
        "browserless": {
            "secret": browserless_token or "",
            "environment": "BROWSERLESS_TOKEN",
            "fetch_endpoint": "https://production-sfo.browserless.io/content",
        },
        "tavily": {
            "secret": tavily_key or "",
            "environment": "TAVILY_API_KEY",
            "search_endpoint": "https://api.tavily.com/search",
            "fetch_endpoint": "https://api.tavily.com/extract",
        },
    }
    for operation, provider in (("SEARCH", web_search_provider), ("FETCH", web_fetch_provider)):
        spec = web_provider_specs.get(provider)
        endpoint_key = f"{operation.lower()}_endpoint"
        if spec is None or endpoint_key not in spec:
            raise ValueError(f"{provider} does not support Personal Web {operation.lower()}")
        secret = str(spec["secret"])
        if not secret:
            raise ValueError(f"{provider} credential is required for Personal Web {operation.lower()}")
        environment_name = str(spec["environment"])
        environment[environment_name] = secret
        prefix = f"HAIFA_PERSONAL_WEB_{operation}"
        environment.update(
            {
                f"{prefix}_ENABLED": "true",
                f"{prefix}_PROVIDER": provider,
                f"{prefix}_ENDPOINT": str(spec[endpoint_key]),
                f"{prefix}_CREDENTIAL": f"env://{environment_name}",
            }
        )
    next_provider_index = 1
    prefix = f"HAIFA_PERSONAL_MODELPROVIDERS_{next_provider_index}"
    environment.update(
        {
            "HAIFA_CODEX_ORIGINATOR": environment_value("HAIFA_CODEX_ORIGINATOR") or "haifa",
            "HAIFA_CODEX_USER_AGENT": environment_value("HAIFA_CODEX_USER_AGENT") or "haifa-agent/1",
            f"{prefix}_ID": "openai-codex",
            f"{prefix}_DISPLAYNAME": "ChatGPT Codex",
            f"{prefix}_MODE": "remote",
            f"{prefix}_ALLOWDETERMINISTIC": "false",
            f"{prefix}_NATIVESTREAMING": "true",
            f"{prefix}_ENDPOINT": "https://chatgpt.com/backend-api/codex",
            f"{prefix}_CREDENTIALREFERENCE": "model-auth://openai-codex/default",
            f"{prefix}_PROXY": "http://127.0.0.1:2081",
            f"{prefix}_APIBINDINGS_0_STYLE": "openai-responses",
            f"{prefix}_APIBINDINGS_0_DIALECT": "openai-codex-responses",
        }
    )
    for model_index, model_id in enumerate(CODEX_MODEL_IDS):
        model_prefix = f"{prefix}_MODELS_{model_index}"
        display_name = model_id.removeprefix("gpt-").replace("-", " ").title()
        environment.update(
            {
                f"{model_prefix}_ID": model_id,
                f"{model_prefix}_DISPLAYNAME": display_name,
                f"{model_prefix}_MODELDISPLAYNAME": display_name,
                f"{model_prefix}_PROVIDERMODELID": model_id,
                f"{model_prefix}_STYLE": "openai-responses",
                f"{model_prefix}_CAPABILITIES_0": "TEXT_CHAT",
                f"{model_prefix}_CAPABILITIES_1": "TOOL_CALLING",
                f"{model_prefix}_CAPABILITIES_2": "REASONING",
                f"{model_prefix}_CONTEXTWINDOW": "272000",
                f"{model_prefix}_MAXOUTPUTTOKENS": "128000",
            }
        )
    next_provider_index = 2
    if bailian is not None:
        bailian_key, workspace_id, region = bailian
        prefix = f"HAIFA_PERSONAL_MODELPROVIDERS_{next_provider_index}"
        endpoint = f"https://{workspace_id}.{region}.maas.aliyuncs.com/compatible-mode/v1"
        environment.update(
            {
                "DASHSCOPE_API_KEY": bailian_key,
                f"{prefix}_ID": "aliyun-bailian",
                f"{prefix}_DISPLAYNAME": "阿里云百炼",
                f"{prefix}_MODE": "remote",
                f"{prefix}_ALLOWDETERMINISTIC": "false",
                f"{prefix}_NATIVESTREAMING": "true",
                f"{prefix}_ENDPOINT": endpoint,
                f"{prefix}_CREDENTIALREFERENCE": "env://DASHSCOPE_API_KEY",
                f"{prefix}_APIBINDINGS_0_STYLE": "openai-chat-completions",
                f"{prefix}_APIBINDINGS_0_DIALECT": "aliyun-bailian-openai-chat",
                f"{prefix}_APIBINDINGS_1_STYLE": "openai-responses",
                f"{prefix}_APIBINDINGS_1_DIALECT": "aliyun-bailian-openai-responses",
                f"{prefix}_MODELS_0_ID": BAILIAN_DEFAULT_MODEL_ID,
                f"{prefix}_MODELS_0_DISPLAYNAME": "Qwen3.7 Max (2026-05-17)",
                f"{prefix}_MODELS_0_PROVIDERMODELID": BAILIAN_DEFAULT_MODEL_ID,
                f"{prefix}_MODELS_0_STYLE": "openai-chat-completions",
                f"{prefix}_MODELS_0_CAPABILITIES_0": "TEXT_CHAT",
                f"{prefix}_MODELS_0_CAPABILITIES_1": "TOOL_CALLING",
                f"{prefix}_MODELS_0_CAPABILITIES_2": "REASONING",
                f"{prefix}_MODELS_0_REASONINGMODE": "ADAPTIVE",
                f"{prefix}_MODELS_0_CONTEXTWINDOW": "1000000",
                f"{prefix}_MODELS_0_MAXOUTPUTTOKENS": "65536",
                f"{prefix}_MODELS_1_ID": "qwen3.7-plus",
                f"{prefix}_MODELS_1_DISPLAYNAME": "Qwen3.7 Plus",
                f"{prefix}_MODELS_1_PROVIDERMODELID": "qwen3.7-plus",
                f"{prefix}_MODELS_1_STYLE": "openai-chat-completions",
                f"{prefix}_MODELS_1_CAPABILITIES_0": "TEXT_CHAT",
                f"{prefix}_MODELS_1_CAPABILITIES_1": "TOOL_CALLING",
                f"{prefix}_MODELS_1_CAPABILITIES_2": "STRUCTURED_OUTPUT",
                f"{prefix}_MODELS_1_CAPABILITIES_3": "REASONING",
                f"{prefix}_MODELS_1_REASONINGMODE": "ADAPTIVE",
                f"{prefix}_MODELS_1_CONTEXTWINDOW": "1000000",
                f"{prefix}_MODELS_1_MAXOUTPUTTOKENS": "65536",
                f"{prefix}_MODELS_2_ID": "qwen3.7-flash",
                f"{prefix}_MODELS_2_DISPLAYNAME": "Qwen3.7 Flash",
                f"{prefix}_MODELS_2_PROVIDERMODELID": "qwen3.7-flash",
                f"{prefix}_MODELS_2_STYLE": "openai-chat-completions",
                f"{prefix}_MODELS_2_CAPABILITIES_0": "TEXT_CHAT",
                f"{prefix}_MODELS_2_CAPABILITIES_1": "TOOL_CALLING",
                f"{prefix}_MODELS_2_CAPABILITIES_2": "STRUCTURED_OUTPUT",
                f"{prefix}_MODELS_2_CAPABILITIES_3": "REASONING",
                f"{prefix}_MODELS_2_REASONINGMODE": "ADAPTIVE",
                f"{prefix}_MODELS_2_CONTEXTWINDOW": "1000000",
                f"{prefix}_MODELS_2_MAXOUTPUTTOKENS": "65536",
                f"{prefix}_MODELS_3_ID": "qwen3-vl-plus",
                f"{prefix}_MODELS_3_DISPLAYNAME": "Qwen3 VL Plus",
                f"{prefix}_MODELS_3_PROVIDERMODELID": "qwen3-vl-plus",
                f"{prefix}_MODELS_3_STYLE": "openai-chat-completions",
                f"{prefix}_MODELS_3_CAPABILITIES_0": "TEXT_CHAT",
                f"{prefix}_MODELS_3_CAPABILITIES_1": "TOOL_CALLING",
                f"{prefix}_MODELS_3_CAPABILITIES_2": "IMAGE_UPLOAD_INPUT",
                f"{prefix}_MODELS_3_CAPABILITIES_3": "IMAGE_URL_INPUT",
                f"{prefix}_MODELS_3_CONTEXTWINDOW": "131072",
                f"{prefix}_MODELS_3_MAXOUTPUTTOKENS": "8192",
                f"{prefix}_MODELS_4_ID": "qwen3.7-max-responses",
                f"{prefix}_MODELS_4_DISPLAYNAME": "Qwen3.7 Max (2026-05-17) · Responses",
                f"{prefix}_MODELS_4_MODELDISPLAYNAME": "Qwen3.7 Max (2026-05-17)",
                f"{prefix}_MODELS_4_PROVIDERMODELID": BAILIAN_DEFAULT_MODEL_ID,
                f"{prefix}_MODELS_4_STYLE": "openai-responses",
                f"{prefix}_MODELS_4_CAPABILITIES_0": "TEXT_CHAT",
                f"{prefix}_MODELS_4_CAPABILITIES_1": "TOOL_CALLING",
                f"{prefix}_MODELS_4_CAPABILITIES_2": "STRUCTURED_OUTPUT",
                f"{prefix}_MODELS_4_CAPABILITIES_3": "REASONING",
                f"{prefix}_MODELS_4_REASONINGMODE": "ADAPTIVE",
                f"{prefix}_MODELS_4_CONTEXTWINDOW": "1000000",
                f"{prefix}_MODELS_4_MAXOUTPUTTOKENS": "65536",
                f"{prefix}_MODELS_5_ID": "qwen3.7-plus-responses",
                f"{prefix}_MODELS_5_DISPLAYNAME": "Qwen3.7 Plus · Responses",
                f"{prefix}_MODELS_5_MODELDISPLAYNAME": "Qwen3.7 Plus",
                f"{prefix}_MODELS_5_PROVIDERMODELID": "qwen3.7-plus",
                f"{prefix}_MODELS_5_STYLE": "openai-responses",
                f"{prefix}_MODELS_5_CAPABILITIES_0": "TEXT_CHAT",
                f"{prefix}_MODELS_5_CAPABILITIES_1": "TOOL_CALLING",
                f"{prefix}_MODELS_5_CAPABILITIES_2": "STRUCTURED_OUTPUT",
                f"{prefix}_MODELS_5_CAPABILITIES_3": "REASONING",
                f"{prefix}_MODELS_5_REASONINGMODE": "ADAPTIVE",
                f"{prefix}_MODELS_5_CONTEXTWINDOW": "1000000",
                f"{prefix}_MODELS_5_MAXOUTPUTTOKENS": "65536",
            }
        )
        for model_index, (model_id, display_name) in enumerate(
            (
                ("qwen3.8-max-0902", "Qwen3.8 Max 0902"),
                ("qwen3.8-max", "Qwen3.8 Max"),
                ("qwen3.8-flash", "Qwen3.8 Flash"),
            ),
            start=6,
        ):
            model_prefix = f"{prefix}_MODELS_{model_index}"
            environment.update(
                {
                    f"{model_prefix}_ID": model_id,
                    f"{model_prefix}_DISPLAYNAME": display_name,
                    f"{model_prefix}_MODELDISPLAYNAME": display_name,
                    f"{model_prefix}_PROVIDERMODELID": model_id,
                    f"{model_prefix}_STYLE": "openai-chat-completions",
                    f"{model_prefix}_CAPABILITIES_0": "TEXT_CHAT",
                    f"{model_prefix}_CAPABILITIES_1": "TOOL_CALLING",
                    f"{model_prefix}_CAPABILITIES_2": "STRUCTURED_OUTPUT",
                    f"{model_prefix}_CAPABILITIES_3": "REASONING",
                    f"{model_prefix}_REASONINGMODE": "ADAPTIVE",
                    f"{model_prefix}_CONTEXTWINDOW": "1048576",
                    f"{model_prefix}_MAXOUTPUTTOKENS": "65536",
                }
            )
    next_provider_index = 3
    if kimi_key is not None:
        prefix = f"HAIFA_PERSONAL_MODELPROVIDERS_{next_provider_index}"
        environment.update(
            {
                "KIMI_API_KEY": kimi_key,
                f"{prefix}_ID": "kimi",
                f"{prefix}_DISPLAYNAME": "Kimi",
                f"{prefix}_MODE": "remote",
                f"{prefix}_ALLOWDETERMINISTIC": "false",
                f"{prefix}_NATIVESTREAMING": "true",
                f"{prefix}_ENDPOINT": "https://api.moonshot.cn/v1",
                f"{prefix}_CREDENTIALREFERENCE": "env://KIMI_API_KEY",
                f"{prefix}_APIBINDINGS_0_STYLE": "openai-chat-completions",
                f"{prefix}_APIBINDINGS_0_DIALECT": "kimi-openai-chat",
            }
        )
        kimi_models = (
            ("kimi-k3", "Kimi K3", "ENABLED", "1000000"),
            ("kimi-k2.7-code", "Kimi K2.7 Code", "ENABLED", "262144"),
            ("kimi-k2.6", "Kimi K2.6", "ENABLED", "262144"),
        )
        for model_index, (model_id, display_name, reasoning_mode, context_window) in enumerate(kimi_models):
            model_prefix = f"{prefix}_MODELS_{model_index}"
            environment.update(
                {
                    f"{model_prefix}_ID": model_id,
                    f"{model_prefix}_DISPLAYNAME": display_name,
                    f"{model_prefix}_MODELDISPLAYNAME": display_name,
                    f"{model_prefix}_PROVIDERMODELID": model_id,
                    f"{model_prefix}_STYLE": "openai-chat-completions",
                    f"{model_prefix}_CAPABILITIES_0": "TEXT_CHAT",
                    f"{model_prefix}_CAPABILITIES_1": "TOOL_CALLING",
                    f"{model_prefix}_CAPABILITIES_2": "REASONING",
                    f"{model_prefix}_REASONINGMODE": reasoning_mode,
                    f"{model_prefix}_CONTEXTWINDOW": context_window,
                    f"{model_prefix}_MAXOUTPUTTOKENS": "131072",
                }
            )
    next_provider_index = 4
    if bigmodel_key is not None:
        prefix = f"HAIFA_PERSONAL_MODELPROVIDERS_{next_provider_index}"
        environment.update(
            {
                "BIGMODEL_API_KEY": bigmodel_key,
                f"{prefix}_ID": "zhipu",
                f"{prefix}_DISPLAYNAME": "智谱 GLM",
                f"{prefix}_MODE": "remote",
                f"{prefix}_ALLOWDETERMINISTIC": "false",
                f"{prefix}_NATIVESTREAMING": "true",
                f"{prefix}_ENDPOINT": "https://open.bigmodel.cn/api/paas/v4",
                f"{prefix}_CREDENTIALREFERENCE": "env://BIGMODEL_API_KEY",
                f"{prefix}_APIBINDINGS_0_STYLE": "openai-chat-completions",
                f"{prefix}_APIBINDINGS_0_DIALECT": "zhipu-openai-chat",
                f"{prefix}_APIBINDINGS_1_STYLE": "anthropic-messages",
                f"{prefix}_APIBINDINGS_1_DIALECT": "zhipu-anthropic-messages",
                f"{prefix}_APIBINDINGS_1_ENDPOINT": "https://open.bigmodel.cn/api/anthropic",
            }
        )
        zhipu_models = (
            ("glm-5.2-chat", "GLM-5.2", "glm-5.2", "openai-chat-completions"),
            ("glm-5.2-anthropic", "GLM-5.2 · Anthropic Messages", "glm-5.2", "anthropic-messages"),
            ("glm-5.1-chat", "GLM-5.1", "glm-5.1", "openai-chat-completions"),
            ("glm-5-chat", "GLM-5", "glm-5", "openai-chat-completions"),
        )
        for model_index, (binding_id, display_name, provider_model_id, style) in enumerate(zhipu_models):
            model_prefix = f"{prefix}_MODELS_{model_index}"
            environment.update(
                {
                    f"{model_prefix}_ID": binding_id,
                    f"{model_prefix}_DISPLAYNAME": display_name,
                    f"{model_prefix}_MODELDISPLAYNAME": "GLM-5.2" if provider_model_id == "glm-5.2" else display_name,
                    f"{model_prefix}_PROVIDERMODELID": provider_model_id,
                    f"{model_prefix}_STYLE": style,
                    f"{model_prefix}_CAPABILITIES_0": "TEXT_CHAT",
                    f"{model_prefix}_CAPABILITIES_1": "TOOL_CALLING",
                    f"{model_prefix}_CAPABILITIES_2": "REASONING",
                    f"{model_prefix}_REASONINGMODE": "ADAPTIVE",
                    f"{model_prefix}_CONTEXTWINDOW": "1000000",
                    f"{model_prefix}_MAXOUTPUTTOKENS": "131072",
                }
            )
    next_provider_index = 5
    if siliconflow_key is not None:
        prefix = f"HAIFA_PERSONAL_MODELPROVIDERS_{next_provider_index}"
        environment.update(
            {
                "SILICONFLOW_API_KEY": siliconflow_key,
                f"{prefix}_ID": "siliconflow",
                f"{prefix}_DISPLAYNAME": "硅基流动 SiliconFlow",
                f"{prefix}_MODE": "remote",
                f"{prefix}_ALLOWDETERMINISTIC": "false",
                f"{prefix}_NATIVESTREAMING": "true",
                f"{prefix}_ENDPOINT": "https://api.siliconflow.cn/v1",
                f"{prefix}_CREDENTIALREFERENCE": "env://SILICONFLOW_API_KEY",
                f"{prefix}_APIBINDINGS_0_STYLE": "openai-chat-completions",
                f"{prefix}_APIBINDINGS_0_DIALECT": "siliconflow-openai-chat",
                f"{prefix}_MODELS_0_ID": SILICONFLOW_MODEL_ID,
                f"{prefix}_MODELS_0_DISPLAYNAME": "DeepSeek V4 Flash",
                f"{prefix}_MODELS_0_MODELDISPLAYNAME": "DeepSeek V4 Flash",
                f"{prefix}_MODELS_0_PROVIDERMODELID": "deepseek-ai/DeepSeek-V4-Flash",
                f"{prefix}_MODELS_0_STYLE": "openai-chat-completions",
                f"{prefix}_MODELS_0_CAPABILITIES_0": "TEXT_CHAT",
                f"{prefix}_MODELS_0_CAPABILITIES_1": "TOOL_CALLING",
                f"{prefix}_MODELS_0_CONTEXTWINDOW": "1000000",
                f"{prefix}_MODELS_0_MAXOUTPUTTOKENS": "8192",
            }
        )
        siliconflow_models = (
            ("siliconflow-deepseek-v4-pro", "DeepSeek V4 Pro", "deepseek-ai/DeepSeek-V4-Pro", "1048576", "393216", ("TEXT_CHAT", "TOOL_CALLING", "STRUCTURED_OUTPUT", "REASONING"), "ENABLED"),
            ("siliconflow-qwen3-vl-32b", "Qwen3 VL 32B Instruct", "Qwen/Qwen3-VL-32B-Instruct", "131072", "8192", ("TEXT_CHAT", "TOOL_CALLING", "STRUCTURED_OUTPUT", "IMAGE_UPLOAD_INPUT"), None),
            ("siliconflow-qwen3-32b", "Qwen3 32B", "Qwen/Qwen3-32B", "131072", "8192", ("TEXT_CHAT", "TOOL_CALLING", "STRUCTURED_OUTPUT", "REASONING"), "ENABLED"),
            ("siliconflow-kimi-k3", "Kimi K3", "moonshotai/Kimi-K3", "262144", "32768", ("TEXT_CHAT", "TOOL_CALLING", "REASONING"), "ENABLED"),
            ("siliconflow-kimi-k2-6", "Kimi K2.6", "moonshotai/Kimi-K2.6", "262144", "32768", ("TEXT_CHAT", "TOOL_CALLING", "REASONING"), "ENABLED"),
            ("siliconflow-glm-5-2", "GLM-5.2", "zai-org/GLM-5.2", "1048576", "131072", ("TEXT_CHAT", "TOOL_CALLING", "STRUCTURED_OUTPUT", "REASONING"), "ENABLED"),
            ("siliconflow-glm-5-1", "GLM-5.1", "zai-org/GLM-5.1", "131072", "32768", ("TEXT_CHAT", "TOOL_CALLING", "STRUCTURED_OUTPUT", "REASONING"), "ENABLED"),
        )
        for model_index, (model_id, display_name, provider_model_id, context_window, max_output_tokens, capabilities, reasoning_mode) in enumerate(siliconflow_models, start=1):
            model_prefix = f"{prefix}_MODELS_{model_index}"
            environment.update(
                {
                    f"{model_prefix}_ID": model_id,
                    f"{model_prefix}_DISPLAYNAME": display_name,
                    f"{model_prefix}_MODELDISPLAYNAME": display_name,
                    f"{model_prefix}_PROVIDERMODELID": provider_model_id,
                    f"{model_prefix}_STYLE": "openai-chat-completions",
                    f"{model_prefix}_CONTEXTWINDOW": context_window,
                    f"{model_prefix}_MAXOUTPUTTOKENS": max_output_tokens,
                    **{f"{model_prefix}_CAPABILITIES_{index}": capability for index, capability in enumerate(capabilities)},
                    **({f"{model_prefix}_REASONINGMODE": reasoning_mode} if reasoning_mode else {}),
                }
            )
    next_provider_index = 6
    if openai is not None:
        openai_base_url, openai_key, openai_model_id = openai
        prefix = f"HAIFA_PERSONAL_MODELPROVIDERS_{next_provider_index}"
        environment.update(
            {
                "OPENAI_BASE_URL": openai_base_url,
                "OPENAI_API_KEY": openai_key,
                "OPENAI_MODEL_ID": openai_model_id,
                f"{prefix}_ID": "local-openai",
                f"{prefix}_DISPLAYNAME": "Local OpenAI Responses Gateway",
                f"{prefix}_MODE": "remote",
                f"{prefix}_ALLOWDETERMINISTIC": "false",
                f"{prefix}_NATIVESTREAMING": "true",
                f"{prefix}_ENDPOINT": openai_base_url,
                f"{prefix}_CREDENTIALREFERENCE": "env://OPENAI_API_KEY",
                f"{prefix}_APIBINDINGS_0_STYLE": "openai-responses",
                f"{prefix}_MODELS_0_ID": "local-openai-responses",
                f"{prefix}_MODELS_0_DISPLAYNAME": "Local OpenAI Responses",
                f"{prefix}_MODELS_0_PROVIDERMODELID": openai_model_id,
                f"{prefix}_MODELS_0_STYLE": "openai-responses",
                f"{prefix}_MODELS_0_CAPABILITIES_0": "TEXT_CHAT",
                f"{prefix}_MODELS_0_CONTEXTWINDOW": "131072",
                f"{prefix}_MODELS_0_MAXOUTPUTTOKENS": "8192",
            }
        )
        next_provider_index += 1
    if antigravity is not None:
        prefix = f"HAIFA_PERSONAL_MODELPROVIDERS_{next_provider_index}"
        environment.update(
            {
                f"{prefix}_ID": "google-antigravity",
                f"{prefix}_DISPLAYNAME": "Google Antigravity Direct (Local Compatibility)",
                f"{prefix}_MODE": "remote",
                f"{prefix}_ALLOWDETERMINISTIC": "false",
                f"{prefix}_NATIVESTREAMING": "true",
                f"{prefix}_ENDPOINT": antigravity.endpoint,
                f"{prefix}_PROXY": antigravity.proxy,
                f"{prefix}_CREDENTIALREFERENCE": "model-auth://google-antigravity/default",
                f"{prefix}_APIBINDINGS_0_STYLE": "google-gemini-generate-content",
                f"{prefix}_APIBINDINGS_0_DIALECT": "antigravity-direct",
                f"{prefix}_MODELS_0_ID": ANTIGRAVITY_DIRECT_MODEL_ID,
                f"{prefix}_MODELS_0_DISPLAYNAME": "Gemini via Antigravity Direct (UNOFFICIAL_LOCAL_COMPAT)",
                f"{prefix}_MODELS_0_MODELDISPLAYNAME": "Gemini Flash",
                f"{prefix}_MODELS_0_PROVIDERMODELID": antigravity.provider_model_id,
                f"{prefix}_MODELS_0_STYLE": "google-gemini-generate-content",
                f"{prefix}_MODELS_0_CAPABILITIES_0": "TEXT_CHAT",
                f"{prefix}_MODELS_0_CAPABILITIES_1": "TOOL_CALLING",
                f"{prefix}_MODELS_0_CAPABILITIES_2": "STRUCTURED_OUTPUT",
                f"{prefix}_MODELS_0_CAPABILITIES_3": "REASONING",
                f"{prefix}_MODELS_0_CAPABILITIES_4": "IMAGE_UPLOAD_INPUT",
                f"{prefix}_MODELS_0_CONTEXTWINDOW": "131072",
                f"{prefix}_MODELS_0_MAXOUTPUTTOKENS": "8192",
            }
        )
    return environment


def resolve_default_model_id(
    requested: str | None,
    bailian: tuple[str, str, str] | None,
    kimi_key: str | None = None,
    bigmodel_key: str | None = None,
    siliconflow_key: str | None = None,
    antigravity: AntigravityConfiguration | None = None,
) -> str:
    selected = requested or DEFAULT_MODEL_ID
    if selected.startswith("qwen") and bailian is None:
        fail("A Qwen default model requires complete Bailian API key, workspace, and region configuration.")
    if selected.startswith("kimi") and kimi_key is None:
        fail("A Kimi default model requires a Kimi API key.")
    if selected.startswith("glm") and bigmodel_key is None:
        fail("A GLM default model requires a BigModel API key.")
    if selected in SILICONFLOW_MODEL_IDS and siliconflow_key is None:
        fail("The SiliconFlow default model requires a SiliconFlow API key.")
    if selected == ANTIGRAVITY_DIRECT_MODEL_ID and antigravity is None:
        fail("The Antigravity Direct default model requires local compatibility testing to be enabled.")
    return selected


def ensure_service(
    records: list[ServiceRecord],
    role: str,
    port: int,
    health_uri: str,
    work_directory: Path,
    command: str | Path,
    arguments: Sequence[str],
    environment: Mapping[str, str],
    timeout_seconds: int,
    value: Paths,
) -> None:
    if http_healthy(health_uri):
        records.append(
            ServiceRecord(role, "reused", listening_process_id(port), health_uri, str(work_directory), None, None)
        )
        return
    if port_open(port):
        fail(f"Port {port} is occupied, but {role} health check failed. No process was stopped.")
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    stdout_path = value.logs / f"{role}-{timestamp}.out.log"
    stderr_path = value.logs / f"{role}-{timestamp}.err.log"
    process = start_process(command, arguments, work_directory, environment, stdout_path, stderr_path)
    wait_for_http(role, health_uri, process, timeout_seconds, stdout_path, stderr_path)
    process_id = listening_process_id(port)
    if process_id is None:
        fail(f"{role} became healthy, but its listening PID could not be identified.")
    records.append(
        ServiceRecord(
            role,
            "started",
            process_id,
            health_uri,
            str(work_directory),
            str(stdout_path),
            str(stderr_path),
        )
    )


def backend_build_arguments(rebuild: bool) -> tuple[str, ...]:
    goals = ("clean", "package") if rebuild else ("package",)
    return (
        "-pl",
        ":haifa-agent-personal-assistant-server",
        "-am",
        "-DskipUnitTests=true",
        *goals,
    )


def backend_launch(
    java: str,
    launch_mode: str,
    server_jar: Path | None,
    environment: Mapping[str, str] | None = None,
) -> BackendLaunch:
    if launch_mode == "jar":
        if server_jar is None:
            fail("The executable Personal Assistant Server JAR is unavailable.")
        return BackendLaunch(java, ("-jar", str(server_jar)), {})
    if launch_mode != "classpath":
        fail(f"Unsupported backend launch mode: {launch_mode}")
    source = os.environ if environment is None else environment
    classpath = str(source.get(DEVELOPMENT_CLASSPATH_ENVIRONMENT, "")).strip()
    if not classpath:
        fail(
            f"{DEVELOPMENT_CLASSPATH_ENVIRONMENT} is required for classpath backend launch. "
            "Run PersonalAssistantRealEnvironmentMain from the IDE instead of invoking this mode directly."
        )
    return BackendLaunch(
        java,
        (EXPECTED_SERVER_START_CLASS,),
        {"CLASSPATH": classpath},
    )


def start_environment(args: argparse.Namespace, value: Paths) -> None:
    if args.rebuild and any(port_open(port) for port in (FRONTEND_PORT, BACKEND_PORT, MCP_PORT)):
        fail(rebuild_port_conflict_message())

    java = required_command("java")
    node = required_command("node")
    npm = required_command("npm")
    maven = required_command("mvn")
    if not value.maven_wrapper.is_file():
        fail(f"Maven wrapper was not found: {value.maven_wrapper}")

    utility = Path(args.utility_mcp_directory).expanduser().resolve(strict=True)
    skill_root = Path(args.personal_skill_root).expanduser().resolve(strict=True)
    if not (utility / "pom.xml").is_file():
        fail(f"Utility MCP pom.xml was not found under: {utility}")
    if not any((candidate / "SKILL.md").is_file() for candidate in skill_root.iterdir() if candidate.is_dir()):
        fail(f"Personal Skill root contains no immediate child with SKILL.md: {skill_root}")
    trusted_manifest = None
    if args.trusted_script_manifest:
        trusted_manifest = Path(args.trusted_script_manifest).expanduser().resolve(strict=True)
        if not trusted_manifest.is_file():
            fail(f"Trusted script manifest is not a file: {trusted_manifest}")

    deepseek_key = read_secret_file(args.deepseek_key_file, "DeepSeek")
    selected_web_providers = {args.web_search_provider, args.web_fetch_provider}
    aliyun_key = (
        read_secret_file(args.aliyun_iqs_key_file, "Aliyun IQS")
        if "aliyun" in selected_web_providers
        else ""
    )
    browserless_token = (
        read_secret_file(args.browserless_key_file, "Browserless")
        if "browserless" in selected_web_providers
        else None
    )
    tavily_key = (
        read_secret_file(args.tavily_key_file, "Tavily")
        if "tavily" in selected_web_providers
        else None
    )
    openai = optional_openai_environment()
    bailian = optional_bailian_configuration(args.bailian_key_file, args.bailian_region)
    kimi_key = optional_secret_file(args.kimi_key_file, "Kimi", "KIMI_API_KEY")
    bigmodel_key = optional_secret_file(args.bigmodel_key_file, "BigModel", "BIGMODEL_API_KEY")
    siliconflow_key = optional_secret_file(
        args.siliconflow_key_file, "SiliconFlow", "SILICONFLOW_API_KEY"
    )
    antigravity = antigravity_configuration()
    default_model_id = resolve_default_model_id(
        args.default_model_id,
        bailian,
        kimi_key,
        bigmodel_key,
        siliconflow_key,
        antigravity,
    )
    continuation = continuation_key(args.continuation_key_file)
    for directory in (value.runtime, value.data, value.logs):
        directory.mkdir(parents=True, exist_ok=True)
        directory.chmod(stat.S_IRWXU)

    server_jar = (
        ensure_executable_server_jar(value, args.rebuild)
        if args.backend_launch_mode == "jar"
        else None
    )

    backend_health_uri = f"http://127.0.0.1:{BACKEND_PORT}/actuator/health"
    if http_healthy(backend_health_uri):
        runtime_server_jar = server_jar
    elif port_open(BACKEND_PORT):
        fail(
            f"Port {BACKEND_PORT} is occupied, but personal-backend health check failed. "
            "No process was stopped."
        )
    else:
        runtime_server_jar = stage_server_jar(server_jar, value) if server_jar is not None else None

    backend_process = backend_launch(
        java,
        args.backend_launch_mode,
        runtime_server_jar,
    )

    serve_script = value.web / "node_modules/serve/build/main.js"
    if not serve_script.is_file():
        print("Installing locked frontend dependencies...")
        run_checked(npm, "ci", cwd=value.web)
    frontend_index = value.web / "dist/index.html"
    if args.rebuild or not frontend_index.is_file():
        print("Building the standalone Personal Assistant frontend...")
        frontend_environment = os.environ.copy()
        frontend_environment["VITE_PERSONAL_ASSISTANT_API_BASE_URL"] = (
            f"http://127.0.0.1:{BACKEND_PORT}/api/v1"
        )
        run_checked(npm, "run", "build", cwd=value.web, environment=frontend_environment)

    records: list[ServiceRecord] = []
    ensure_service(
        records,
        "utility-mcp",
        MCP_PORT,
        f"http://127.0.0.1:{MCP_PORT}/actuator/health",
        utility,
        maven,
        ("spring-boot:run",),
        {
            "UTILITY_MCP_PORT": str(MCP_PORT),
            "UTILITY_MCP_PROXY_URL": args.utility_mcp_proxy_url,
            "UTILITY_MCP_PROXY_PROVIDERS": args.utility_mcp_proxy_providers,
        },
        args.startup_timeout_seconds,
        value,
    )
    personal_backend_environment = backend_environment(
        deepseek_key,
        default_model_id,
        openai,
        aliyun_key,
        continuation,
        value,
        skill_root,
        trusted_manifest,
        bailian,
        kimi_key,
        bigmodel_key,
        browserless_token,
        tavily_key,
        args.web_search_provider,
        args.web_fetch_provider,
        siliconflow_key=siliconflow_key,
        antigravity=antigravity,
    )
    personal_backend_environment.update(backend_process.environment)
    ensure_service(
        records,
        "personal-backend",
        BACKEND_PORT,
        backend_health_uri,
        value.runtime / "backend",
        backend_process.command,
        backend_process.arguments,
        personal_backend_environment,
        args.startup_timeout_seconds,
        value,
    )
    ensure_service(
        records,
        "personal-web",
        FRONTEND_PORT,
        f"http://127.0.0.1:{FRONTEND_PORT}/",
        value.web,
        node,
        (
            str(serve_script),
            "-s",
            str(value.web / "dist"),
            "-l",
            f"tcp://127.0.0.1:{FRONTEND_PORT}",
            "--no-clipboard",
        ),
        {},
        args.startup_timeout_seconds,
        value,
    )
    atomic_json(value.state, [asdict(record) for record in records])

    print("\nReal Personal Assistant environment is ready.")
    for record in records:
        print(f"  {record.Role:<18} {record.Status:<8} PID={record.Pid or '-':<8} {record.Url}")
    print("\nWork directories:")
    print(f"  Repository:       {value.repository}")
    print(f"  Personal Web:     {value.web}")
    print(f"  Personal Server:  {value.server}")
    print(f"  Backend runtime:  {value.runtime / 'backend'}")
    print(f"  Utility MCP:      {utility}")
    print(f"  Utility Proxy:    {args.utility_mcp_proxy_url} ({args.utility_mcp_proxy_providers})")
    print(f"  Personal Skills:  {skill_root}")
    if trusted_manifest:
        print(f"  Trust Manifest:   {trusted_manifest}")
    print(f"  Runtime data:     {value.data}")
    print(f"  Runtime logs:     {value.logs}")
    print("\nAccess addresses:")
    print(f"  Personal Web:     http://127.0.0.1:{FRONTEND_PORT}/")
    print(f"  Personal API:     http://127.0.0.1:{BACKEND_PORT}/api/v1")
    print(f"  Backend health:   http://127.0.0.1:{BACKEND_PORT}/actuator/health")
    print(f"  Backend OpenAPI:  http://127.0.0.1:{BACKEND_PORT}/api/v1/openapi.json")
    print(f"  Utility MCP:      http://127.0.0.1:{MCP_PORT}/mcp")
    print(f"  MCP health:       http://127.0.0.1:{MCP_PORT}/actuator/health")
    print(
        "  Web Tools:        "
        f"web.search ({args.web_search_provider}), web.fetch ({args.web_fetch_provider})"
    )
    print(f"\nState: {value.state}")
    print(f"Logs:  {value.logs}")
    print("Secrets were loaded into child process environments only and were not printed.")


def main(arguments: Iterable[str] | None = None) -> int:
    try:
        args = parser().parse_args(arguments)
        validate_arguments(args)
        value = paths()
        if args.stop:
            stop_environment(args, value)
        else:
            start_environment(args, value)
        return 0
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as exception:
        print(f"Error: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
