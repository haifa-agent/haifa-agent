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
CONTINUATION_KEY_ENVIRONMENT = "HAIFA_PERSONAL_CONTINUATION_KEY"
EXPECTED_SERVER_START_CLASS = (
    "io.haifa.agent.personalassistant.server.PersonalAssistantServerApplication"
)
BACKEND_LAUNCH_MODES = ("jar", "classpath")
DEVELOPMENT_CLASSPATH_ENVIRONMENT = "HAIFA_PERSONAL_DEV_CLASSPATH"


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
        "Rebuild requires ports 20000 and 20001 to be free.\n"
        "Stop the running environment first, then rebuild:\n"
        "  PowerShell: .\\scripts\\start-real-environment.ps1 --stop\n"
        "              .\\scripts\\start-real-environment.ps1 --rebuild\n"
        "  Bash:       ./scripts/start-real-environment.sh --stop\n"
        "              ./scripts/start-real-environment.sh --rebuild"
    )


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Start, reuse, validate, or stop the real Personal Assistant environment."
    )
    result.add_argument(
        "--continuation-key-file",
        default=os.getenv("HAIFA_PERSONAL_CONTINUATION_KEY_FILE", "").strip(),
        help=(
            "Optional KEY=VALUE file that persists HAIFA_PERSONAL_CONTINUATION_KEY. "
            "Defaults to local-tmp/personal-assistant-real/continuation-key.env."
        ),
    )
    result.add_argument(
        "--default-model-id",
        default=os.getenv("HAIFA_PERSONAL_DEFAULT_MODEL_ID", "").strip() or None,
    )
    result.add_argument(
        "--bailian-region",
        default=os.getenv("ALIYUN_BAILIAN_REGION", "cn-beijing"),
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


ENVIRONMENT_NAME_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def read_key_file(path: Path, environment_name: str, label: str) -> str:
    """Read the single KEY=VALUE entry that a persisted key file must contain."""
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line.removeprefix("export ").lstrip()
        if "=" not in line:
            fail(f"{label} key file line {line_number} must use KEY=VALUE format.")
        name, raw_value = (part.strip() for part in line.split("=", 1))
        if not ENVIRONMENT_NAME_PATTERN.fullmatch(name) or name != environment_name:
            fail(f"{label} key file line {line_number} contains an unexpected variable name.")
        if name in values:
            fail(f"{label} key file contains duplicate {name}.")
        if len(raw_value) >= 2 and raw_value[0] == raw_value[-1] and raw_value[0] in {'"', "'"}:
            raw_value = raw_value[1:-1]
        elif raw_value.startswith(('"', "'")) or raw_value.endswith(('"', "'")):
            fail(f"{label} key file line {line_number} contains unmatched quotes.")
        if not raw_value:
            fail(f"{label} key file variable {name} is empty.")
        values[name] = raw_value
    value = values.get(environment_name, "")
    if not value:
        fail(f"{label} key file does not define {environment_name}: {path}")
    return value


def required_environment_value(name: str) -> str:
    value = environment_value(name)
    if not value:
        fail(f"Required environment variable {name} is not set.")
    return value


def optional_environment_value(name: str) -> str | None:
    return environment_value(name) or None


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
    default_region: str = "cn-beijing",
    environment: Mapping[str, str] | None = None,
) -> tuple[str, str, str] | None:
    source = {
        name: (environment_value(name) if environment is None else environment.get(name, "").strip())
        for name in ("DASHSCOPE_API_KEY", "ALIYUN_BAILIAN_WORKSPACE_ID", "ALIYUN_BAILIAN_REGION")
    }
    if not source["DASHSCOPE_API_KEY"] and not source["ALIYUN_BAILIAN_WORKSPACE_ID"]:
        return None
    source["ALIYUN_BAILIAN_REGION"] = source["ALIYUN_BAILIAN_REGION"] or default_region.strip()
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


def continuation_key_path(value: Paths) -> Path:
    return value.runtime / "continuation-key.env"


def validate_continuation_key(value: str, label: str) -> str:
    try:
        decoded = base64.b64decode(value, validate=True)
    except ValueError as exception:
        raise RuntimeError(f"{label} must be Base64-encoded 32 bytes.") from exception
    if len(decoded) != 32:
        fail(f"{label} must decode to exactly 32 bytes.")
    return value


def continuation_key(configured_file: str, default_path: Path) -> str:
    injected = environment_value(CONTINUATION_KEY_ENVIRONMENT)
    if injected:
        return validate_continuation_key(injected, CONTINUATION_KEY_ENVIRONMENT)
    path = Path(configured_file).expanduser() if configured_file.strip() else default_path
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        encoded = base64.b64encode(secrets.token_bytes(32)).decode("ascii")
        path.write_text(f"{CONTINUATION_KEY_ENVIRONMENT}={encoded}\n", encoding="ascii")
        restrict_secret_file(path)
        print(f"Created a persistent continuation key file: {path}")
    return validate_continuation_key(
        read_key_file(path, CONTINUATION_KEY_ENVIRONMENT, "Continuation"), str(path)
    )


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
    """Build runtime-only inputs; provider/model facts stay in packaged Catalog and deployment YAML."""
    environment = {
        "DEEPSEEK_API_KEY": deepseek_key,
        CONTINUATION_KEY_ENVIRONMENT: continuation,
        "HAIFA_PERSONAL_DATA_DIR": str(value.data),
        "HAIFA_PERSONAL_DEFAULT_MODEL_ID": default_model_id,
        "HAIFA_PERSONAL_ALLOW_INSECURE_LOOPBACK_MODEL": "true",
        "HAIFA_PERSONAL_TRUSTED_SCRIPT_MANIFEST": str(trusted_manifest or ""),
        "HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED": "true",
        "HAIFA_PERSONAL_WEB_SEARCH_ENABLED": "true",
        "HAIFA_PERSONAL_WEB_SEARCH_PROVIDER_ID": web_search_provider,
        "HAIFA_PERSONAL_WEB_FETCH_ENABLED": "true",
        "HAIFA_PERSONAL_WEB_FETCH_PROVIDER_ID": web_fetch_provider,
        "HAIFA_CODEX_ORIGINATOR": environment_value("HAIFA_CODEX_ORIGINATOR") or "haifa",
        "HAIFA_CODEX_USER_AGENT": environment_value("HAIFA_CODEX_USER_AGENT") or "haifa-agent/1",
    }
    for name, secret in (
        ("KIMI_API_KEY", kimi_key),
        ("BIGMODEL_API_KEY", bigmodel_key),
        ("SILICONFLOW_API_KEY", siliconflow_key),
        ("TAVILY_API_KEY", tavily_key),
        ("BROWSERLESS_TOKEN", browserless_token),
    ):
        if secret:
            environment[name] = secret
    if bailian:
        environment["DASHSCOPE_API_KEY"] = bailian[0]
        environment["ALIYUN_BAILIAN_WORKSPACE_ID"] = bailian[1]
        environment["ALIYUN_BAILIAN_REGION"] = bailian[2]
        environment["HAIFA_PERSONAL_BAILIAN_ENDPOINT"] = (
            f"https://{bailian[1]}.{bailian[2]}.maas.aliyuncs.com/compatible-mode/v1"
        )
    if antigravity:
        environment["HAIFA_ANTIGRAVITY_MODEL_ENDPOINT"] = antigravity.endpoint
        environment["HAIFA_ANTIGRAVITY_PROXY_URL"] = antigravity.proxy
        environment["HAIFA_ANTIGRAVITY_MODEL"] = antigravity.provider_model_id
    return environment


def resolve_default_model_id(
    requested: str | None,
    bailian: tuple[str, str, str] | None,
    kimi_key: str | None = None,
    bigmodel_key: str | None = None,
    siliconflow_key: str | None = None,
    antigravity: AntigravityConfiguration | None = None,
) -> str:
    deployment = (
        Path(__file__).resolve().parents[1]
        / "haifa-agent-applications"
        / "haifa-agent-personal-assistant-server"
        / "src"
        / "main"
        / "resources"
        / "application.yml"
    )
    match = re.search(r"default-model-id:\s*\$\{[^:}]+:([^}]+)}", deployment.read_text(encoding="utf-8"))
    selected = requested or (match.group(1).strip() if match else "")
    if not selected:
        fail("Personal Assistant deployment must declare a default model Binding.")
    if selected.startswith("qwen") and bailian is None:
        fail("A Qwen default model requires complete Bailian API key, workspace, and region configuration.")
    if selected.startswith("kimi") and kimi_key is None:
        fail("A Kimi default model requires a Kimi API key.")
    if selected.startswith("glm") and bigmodel_key is None:
        fail("A GLM default model requires a BigModel API key.")
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
    if args.rebuild and any(port_open(port) for port in (FRONTEND_PORT, BACKEND_PORT)):
        fail(rebuild_port_conflict_message())

    java = required_command("java")
    node = required_command("node")
    npm = required_command("npm")
    if not value.maven_wrapper.is_file():
        fail(f"Maven wrapper was not found: {value.maven_wrapper}")

    trusted_manifest = None
    if args.trusted_script_manifest:
        trusted_manifest = Path(args.trusted_script_manifest).expanduser().resolve(strict=True)
        if not trusted_manifest.is_file():
            fail(f"Trusted script manifest is not a file: {trusted_manifest}")

    deepseek_key = required_environment_value("DEEPSEEK_API_KEY")
    selected_web_providers = {args.web_search_provider, args.web_fetch_provider}
    aliyun_key = (
        required_environment_value("ALIYUN_IQS_API_KEY")
        if "aliyun" in selected_web_providers
        else ""
    )
    browserless_token = (
        required_environment_value("BROWSERLESS_TOKEN")
        if "browserless" in selected_web_providers
        else None
    )
    tavily_key = (
        required_environment_value("TAVILY_API_KEY")
        if "tavily" in selected_web_providers
        else None
    )
    openai = optional_openai_environment()
    bailian = optional_bailian_configuration(args.bailian_region)
    kimi_key = optional_environment_value("KIMI_API_KEY")
    bigmodel_key = optional_environment_value("BIGMODEL_API_KEY")
    siliconflow_key = optional_environment_value("SILICONFLOW_API_KEY")
    antigravity = antigravity_configuration()
    default_model_id = resolve_default_model_id(
        args.default_model_id,
        bailian,
        kimi_key,
        bigmodel_key,
        siliconflow_key,
        antigravity,
    )
    continuation = continuation_key(args.continuation_key_file, continuation_key_path(value))
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
    personal_backend_environment = backend_environment(
        deepseek_key,
        default_model_id,
        openai,
        aliyun_key,
        continuation,
        value,
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
    if trusted_manifest:
        print(f"  Trust Manifest:   {trusted_manifest}")
    print(f"  Runtime data:     {value.data}")
    print(f"  Runtime logs:     {value.logs}")
    print("\nAccess addresses:")
    print(f"  Personal Web:     http://127.0.0.1:{FRONTEND_PORT}/")
    print(f"  Personal API:     http://127.0.0.1:{BACKEND_PORT}/api/v1")
    print(f"  Backend health:   http://127.0.0.1:{BACKEND_PORT}/actuator/health")
    print(f"  Backend OpenAPI:  http://127.0.0.1:{BACKEND_PORT}/api/v1/openapi.json")
    print(
        "  Web Tools:        "
        f"web_search ({args.web_search_provider}), web_fetch ({args.web_fetch_provider})"
    )
    print(f"\nState: {value.state}")
    print(f"Logs:  {value.logs}")
    print("Credentials were read from the process environment and were never printed.")


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
