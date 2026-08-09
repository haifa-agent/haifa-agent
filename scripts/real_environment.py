#!/usr/bin/env python3
"""Cross-platform Personal Assistant real-environment lifecycle."""

from __future__ import annotations

import argparse
import base64
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
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence

FRONTEND_PORT = 20000
BACKEND_PORT = 20001
MCP_PORT = 20002
DEFAULT_MODEL_ID = "deepseek-responses-flash"
SUPPORTED_DEFAULT_MODEL_IDS = (
    "deepseek-chat-pro",
    "deepseek-chat-flash",
    "deepseek-responses-flash",
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
    command_token: str


@dataclass(frozen=True)
class ServiceRecord:
    Role: str
    Status: str
    Pid: int | None
    Url: str
    WorkDirectory: str
    Stdout: str | None
    Stderr: str | None


def warn(message: str) -> None:
    print(f"Warning: {message}", file=sys.stderr)


def fail(message: str) -> "NoReturn":
    raise RuntimeError(message)


def rebuild_port_conflict_message() -> str:
    return (
        "Rebuild requires ports 20000, 20001, and 20002 to be free.\n"
        "Stop the running environment first, then rebuild:\n"
        "  PowerShell: .\\scripts\\start-real-environment.ps1 -Stop\n"
        "              .\\scripts\\start-real-environment.ps1 -Rebuild\n"
        "  Bash:       ./scripts/start-real-environment.sh --stop\n"
        "              ./scripts/start-real-environment.sh --rebuild"
    )


def parser() -> argparse.ArgumentParser:
    windows = os.name == "nt"
    home = Path.home()
    workspace = Path("D:/workspace") if windows else home / "workspace"
    agents = Path("D:/agents") if windows else home / "agents"
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
        default=os.getenv("HAIFA_PERSONAL_DEFAULT_MODEL_ID", DEFAULT_MODEL_ID),
    )
    result.add_argument(
        "--aliyun-iqs-key-file",
        default=os.getenv("HAIFA_ALIYUN_IQS_KEY_FILE", str(workspace / "ss-aliyun-iqs.txt")),
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
    if definition.command_token.lower() not in command.lower():
        fail(
            f"{definition.role} PID {process_id} command line does not contain "
            f"'{definition.command_token}'. No process was stopped."
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
        ServiceDefinition("personal-web", FRONTEND_PORT, "node.exe" if os.name == "nt" else "node", str(value.web)),
        ServiceDefinition("personal-backend", BACKEND_PORT, "java.exe" if os.name == "nt" else "java", str(value.server)),
        ServiceDefinition(
            "utility-mcp",
            MCP_PORT,
            "java.exe" if os.name == "nt" else "java",
            "org.wrj.haifa.ai.utilitymcp.UtilityMcpServerApplication",
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


def backend_environment(
    deepseek_key: str,
    default_model_id: str,
    openai: tuple[str, str, str] | None,
    aliyun_key: str,
    continuation: str,
    value: Paths,
    skill_root: Path,
    trusted_manifest: Path | None,
) -> dict[str, str]:
    environment = {
        "DEEPSEEK_API_KEY": deepseek_key,
        "ALIYUN_IQS_API_KEY": aliyun_key,
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
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_DISPLAYNAME": "DeepSeek Chat Pro",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_PROVIDERMODELID": "deepseek-v4-pro",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_STYLE": "openai-chat-completions",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_CAPABILITIES_0": "TEXT_CHAT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_CAPABILITIES_1": "TOOL_CALLING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_CAPABILITIES_2": "STRUCTURED_OUTPUT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_CAPABILITIES_3": "REASONING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_CONTEXTWINDOW": "131072",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_MAXOUTPUTTOKENS": "8192",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_ID": "deepseek-chat-flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_DISPLAYNAME": "DeepSeek Chat Flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_PROVIDERMODELID": "deepseek-v4-flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_STYLE": "openai-chat-completions",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_CAPABILITIES_0": "TEXT_CHAT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_CAPABILITIES_1": "TOOL_CALLING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_CAPABILITIES_2": "STRUCTURED_OUTPUT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_CAPABILITIES_3": "REASONING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_CONTEXTWINDOW": "131072",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_MAXOUTPUTTOKENS": "8192",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_ID": "deepseek-responses-flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_DISPLAYNAME": "DeepSeek Responses Flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_PROVIDERMODELID": "deepseek-v4-flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_STYLE": "openai-responses",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_CAPABILITIES_0": "TEXT_CHAT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_CAPABILITIES_1": "TOOL_CALLING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_CAPABILITIES_2": "STRUCTURED_OUTPUT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_CAPABILITIES_3": "REASONING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_CONTEXTWINDOW": "131072",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_2_MAXOUTPUTTOKENS": "8192",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_ID": "deepseek-anthropic-flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_DISPLAYNAME": "DeepSeek Anthropic Messages Flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_PROVIDERMODELID": "deepseek-v4-flash",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_STYLE": "anthropic-messages",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_CAPABILITIES_0": "TEXT_CHAT",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_CAPABILITIES_1": "TOOL_CALLING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_CAPABILITIES_2": "REASONING",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_CONTEXTWINDOW": "131072",
        "HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_3_MAXOUTPUTTOKENS": "8192",
        "HAIFA_PERSONAL_ALLOW_INSECURE_LOOPBACK_MODEL": "true",
        "HAIFA_PERSONAL_WEB_ENABLED": "true",
        "HAIFA_PERSONAL_WEB_CREDENTIAL": "env://ALIYUN_IQS_API_KEY",
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
    if openai is not None:
        openai_base_url, openai_key, openai_model_id = openai
        environment.update(
            {
                "OPENAI_BASE_URL": openai_base_url,
                "OPENAI_API_KEY": openai_key,
                "OPENAI_MODEL_ID": openai_model_id,
                "HAIFA_PERSONAL_MODELPROVIDERS_1_ID": "local-openai",
                "HAIFA_PERSONAL_MODELPROVIDERS_1_DISPLAYNAME": "Local OpenAI Responses Gateway",
                "HAIFA_PERSONAL_MODELPROVIDERS_1_MODE": "remote",
                "HAIFA_PERSONAL_MODELPROVIDERS_1_ALLOWDETERMINISTIC": "false",
                "HAIFA_PERSONAL_MODELPROVIDERS_1_NATIVESTREAMING": "true",
                "HAIFA_PERSONAL_MODELPROVIDERS_1_ENDPOINT": openai_base_url,
                "HAIFA_PERSONAL_MODELPROVIDERS_1_CREDENTIALREFERENCE": "env://OPENAI_API_KEY",
                "HAIFA_PERSONAL_MODELPROVIDERS_1_APIBINDINGS_0_STYLE": "openai-responses",
                "HAIFA_PERSONAL_MODELPROVIDERS_1_MODELS_0_ID": "local-openai-responses",
                "HAIFA_PERSONAL_MODELPROVIDERS_1_MODELS_0_DISPLAYNAME": "Local OpenAI Responses",
                "HAIFA_PERSONAL_MODELPROVIDERS_1_MODELS_0_PROVIDERMODELID": openai_model_id,
                "HAIFA_PERSONAL_MODELPROVIDERS_1_MODELS_0_STYLE": "openai-responses",
                "HAIFA_PERSONAL_MODELPROVIDERS_1_MODELS_0_CAPABILITIES_0": "TEXT_CHAT",
                "HAIFA_PERSONAL_MODELPROVIDERS_1_MODELS_0_CONTEXTWINDOW": "131072",
                "HAIFA_PERSONAL_MODELPROVIDERS_1_MODELS_0_MAXOUTPUTTOKENS": "8192",
            }
        )
    return environment


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
    aliyun_key = read_secret_file(args.aliyun_iqs_key_file, "Aliyun IQS")
    openai = optional_openai_environment()
    continuation = continuation_key(args.continuation_key_file)
    for directory in (value.runtime, value.data, value.logs):
        directory.mkdir(parents=True, exist_ok=True)
        directory.chmod(stat.S_IRWXU)

    server_jar = latest_server_jar(value)
    if args.rebuild or server_jar is None:
        print("Building the Personal Assistant backend...")
        run_checked(
            value.maven_wrapper,
            *backend_build_arguments(args.rebuild),
            cwd=value.repository,
        )
        server_jar = latest_server_jar(value)
        if server_jar is None:
            fail("Backend build completed without producing an executable server JAR.")

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
    ensure_service(
        records,
        "personal-backend",
        BACKEND_PORT,
        f"http://127.0.0.1:{BACKEND_PORT}/actuator/health",
        value.server,
        java,
        ("-jar", str(server_jar)),
        backend_environment(
            deepseek_key,
            args.default_model_id,
            openai,
            aliyun_key,
            continuation,
            value,
            skill_root,
            trusted_manifest,
        ),
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
    print("  Web Tools:        web.search, web.fetch (Aliyun IQS)")
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
