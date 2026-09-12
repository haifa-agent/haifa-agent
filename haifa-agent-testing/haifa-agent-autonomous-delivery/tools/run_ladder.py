#!/usr/bin/env python3
"""One-command capability-ladder evaluation: preflight checks first, then every case.

Actions:
  check   run the preflight checks only (environment, toolchain, assets, runner tests, gates)
  run     run the preflight checks and then evaluate every selected case with the coding agent

Environment variables (a flag of the same name always wins):

| Variable | Required | Meaning |
| --- | --- | --- |
| HAIFA_LADDER_ALLOW_REAL_PROVIDER | yes for `run` | Must be `true`: a real run calls a provider and costs money |
| HAIFA_LADDER_AGENT | yes unless discovered | Coding agent launcher, e.g. `~/.haifa-agent/coding/haifa-coding.cmd` |
| HAIFA_LADDER_MODEL | no | Model id passed as `--model`; defaults to the agent configuration |
| HAIFA_LADDER_CREDENTIAL_ENV | no | Credential variable to verify; inferred from the model id when unset |
| HAIFA_LADDER_APPROVAL | no | Approval mode, default `auto` (a ladder run must stay non-interactive) |
| HAIFA_LADDER_CASES | no | Comma separated case ids or glob patterns, default every case |
| HAIFA_LADDER_REPEAT | no | Runs per case, default 1 |
| HAIFA_LADDER_TIMEOUT_SCALE | no | Multiplies the per-case budget, default 1.0 |
| HAIFA_LADDER_OUTPUT | no | Report directory, default `local-tmp/autonomous-delivery-ladder/<timestamp>` |
| HAIFA_LADDER_CACHE_DIR | no | Asset cache directory, default `local-tmp/autonomous-delivery-assets` |
| HAIFA_LADDER_ASSETS_DIR | no | Use an existing verified asset checkout instead of downloading |

The console never shows the task statement, more than one truncated line of agent output, or any
value of a credential variable. Per-case agent logs are written to the report directory with every
known credential value redacted.
"""

from __future__ import annotations

import argparse
import fnmatch
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import threading
import time
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

TOOLS_ROOT = Path(__file__).resolve().parent
MODULE_ROOT = TOOLS_ROOT.parent
REPOSITORY_ROOT = MODULE_ROOT.parents[1]
HEARTBEAT_SECONDS = 15
AGENT_SELF_LIMIT_RATIO = 0.9
LAST_LINE_WIDTH = 96

# Model id prefix -> credential variable of the provider that serves it (see the CLI distribution
# configuration). Providers authenticated through `model-auth://` have no credential variable.
CREDENTIAL_BY_PREFIX = {
    "glm-": "BIGMODEL_API_KEY",
    "qwen": "DASHSCOPE_API_KEY",
    "kimi-": "KIMI_API_KEY",
    "siliconflow-": "SILICONFLOW_API_KEY",
    "tokenrhythm-": "TK_API_KEY",
}
CREDENTIAL_FILE_PREFIXES = ("deepseek", "gpt-", "antigravity")
# `ask` maps to the LOW approval threshold and reads the answer from stdin, which the evaluation closes.
NON_INTERACTIVE_APPROVALS = frozenset({"auto", "deny"})
RESULT_STATUSES = frozenset({"PASSED", "FAILED", "INCOMPLETE_BUDGET"})


def load(module_name: str):
    """Import a sibling tool module by path so that every tool stays a standalone script."""
    spec = importlib.util.spec_from_file_location(module_name, TOOLS_ROOT / f"{module_name}.py")
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


run_case = load("run_case")
fetch_assets = load("fetch_assets")


@dataclass
class Settings:
    action: str
    allow_real_provider: bool
    agent: str | None
    model: str | None
    credential_env: str | None
    approval: str
    case_patterns: list[str]
    repeat: int
    timeout_scale: float
    output_dir: Path
    cache_dir: Path
    assets_dir: Path | None
    skip_gates: bool
    gate_repeat: int
    rehearse: bool
    keep_workdir: bool
    allow_unpinned_assets: bool


@dataclass
class AgentOutcome:
    exit_code: int | None
    lines: int
    timed_out: bool
    duration_seconds: float
    last_line: str


@dataclass
class CaseOutcome:
    case_id: str
    level: str
    attempt: int
    status: str
    duration_seconds: float
    agent: AgentOutcome | None
    checks_passed: int
    checks_total: int
    failures: list[str] = field(default_factory=list)
    changed_sources: list[str] = field(default_factory=list)
    reasons: dict[str, str] = field(default_factory=dict)
    detail: str = ""


def environment_flag(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in {"1", "true", "yes", "on"}


def default_agent() -> str | None:
    """Return the packaged local launcher, or one found on PATH."""
    launcher = Path.home() / ".haifa-agent" / "coding" / ("haifa-coding.cmd" if os.name == "nt" else "haifa-coding")
    if launcher.is_file():
        return str(launcher)
    return shutil.which("haifa-coding")


def credential_variable(model: str | None, explicit: str | None) -> tuple[str | None, str]:
    """Return the credential variable to verify and how it was determined."""
    if explicit:
        return explicit, "HAIFA_LADDER_CREDENTIAL_ENV"
    if not model:
        return None, "model not pinned, the agent configuration decides"
    lowered = model.strip().lower()
    for prefix, variable in CREDENTIAL_BY_PREFIX.items():
        if lowered.startswith(prefix):
            return variable, f"inferred from model id {model}"
    if lowered.startswith(CREDENTIAL_FILE_PREFIXES):
        return None, f"{model} authenticates through ~/.haifa-agent/auth.json"
    return None, f"unknown provider for model id {model}"


def absolute(value: str | Path) -> Path:
    """Every path is resolved before a case starts: the agent runs with cwd set to its workspace."""
    return Path(value).expanduser().resolve()


def resolve_agent(value: str | None) -> str | None:
    """Return an agent launcher that stays valid after the working directory changes."""
    if not value:
        return None
    candidate = Path(value).expanduser()
    if candidate.is_file():
        return str(candidate.resolve())
    found = shutil.which(value)
    return str(absolute(found)) if found else value


def integer(name: str, fallback: int) -> int:
    value = os.environ.get(name, "").strip()
    try:
        return int(value) if value else fallback
    except ValueError:
        raise SystemExit(f"{name} must be an integer, got {value!r}") from None


def number(name: str, fallback: float) -> float:
    value = os.environ.get(name, "").strip()
    try:
        return float(value) if value else fallback
    except ValueError:
        raise SystemExit(f"{name} must be a number, got {value!r}") from None


def resolve_settings(arguments: argparse.Namespace) -> Settings:
    timestamp = time.strftime("%Y%m%dT%H%M%S")
    output = arguments.output or os.environ.get("HAIFA_LADDER_OUTPUT")
    cache = arguments.cache_dir or os.environ.get("HAIFA_LADDER_CACHE_DIR")
    assets = arguments.assets_dir or os.environ.get("HAIFA_LADDER_ASSETS_DIR")
    cases = arguments.cases or os.environ.get("HAIFA_LADDER_CASES", "")
    return Settings(
        action=arguments.action,
        allow_real_provider=environment_flag("HAIFA_LADDER_ALLOW_REAL_PROVIDER"),
        agent=resolve_agent(arguments.agent or os.environ.get("HAIFA_LADDER_AGENT") or default_agent()),
        model=arguments.model or os.environ.get("HAIFA_LADDER_MODEL") or os.environ.get("HAIFA_MODEL_ID"),
        credential_env=arguments.credential_env or os.environ.get("HAIFA_LADDER_CREDENTIAL_ENV"),
        approval=arguments.approval or os.environ.get("HAIFA_LADDER_APPROVAL", "auto"),
        case_patterns=[pattern.strip() for pattern in cases.split(",") if pattern.strip()],
        repeat=arguments.repeat if arguments.repeat is not None else integer("HAIFA_LADDER_REPEAT", 1),
        timeout_scale=(
            arguments.timeout_scale if arguments.timeout_scale is not None else number("HAIFA_LADDER_TIMEOUT_SCALE", 1.0)
        ),
        output_dir=absolute(output) if output else REPOSITORY_ROOT / "local-tmp" / "autonomous-delivery-ladder" / timestamp,
        cache_dir=absolute(cache) if cache else REPOSITORY_ROOT / "local-tmp" / "autonomous-delivery-assets",
        assets_dir=absolute(assets) if assets else None,
        skip_gates=arguments.skip_gates,
        gate_repeat=arguments.gate_repeat,
        rehearse=arguments.rehearse,
        keep_workdir=arguments.keep_workdir,
        allow_unpinned_assets=arguments.allow_unpinned_assets,
    )


def secret_values(settings: Settings) -> list[str]:
    """Every credential value that must never reach a log file or the console."""
    names = set(CREDENTIAL_BY_PREFIX.values())
    if settings.credential_env:
        names.add(settings.credential_env)
    return [value for value in (os.environ.get(name, "") for name in names) if len(value) >= 8]


def redact(text: str, secrets: list[str]) -> str:
    for secret in secrets:
        text = text.replace(secret, "***")
    return text


def parse_diagnostics(stderr: str) -> tuple[list[str], dict[str, str]]:
    """Read the changed source files and the failure reasons from the acceptance diagnostics line."""
    for line in reversed((stderr or "").splitlines()):
        if line.startswith("DIAGNOSTICS "):
            try:
                payload = json.loads(line[len("DIAGNOSTICS "):])
            except json.JSONDecodeError:
                return [], {}
            return list(payload.get("changedSources") or []), dict(payload.get("details") or {})
    return [], {}


def say(message: str = "") -> None:
    print(message, flush=True)


def duration(seconds: float) -> str:
    seconds = int(seconds)
    if seconds < 60:
        return f"{seconds}s"
    if seconds < 3600:
        return f"{seconds // 60}m{seconds % 60:02d}s"
    return f"{seconds // 3600}h{(seconds % 3600) // 60:02d}m"


# --------------------------------------------------------------------------------------- preflight


def missing_environment(settings: Settings) -> list[str]:
    """Return the human readable reasons why the run cannot start yet."""
    problems: list[str] = []
    if settings.action == "run" and not settings.rehearse and not settings.allow_real_provider:
        problems.append(
            "HAIFA_LADDER_ALLOW_REAL_PROVIDER is not true: a ladder run calls a real provider and costs money"
        )
    if settings.repeat < 1:
        problems.append(f"repeat must be at least 1, got {settings.repeat}: nothing would be evaluated")
    if settings.gate_repeat < 1:
        problems.append(f"gate repeat must be at least 1, got {settings.gate_repeat}")
    if settings.timeout_scale <= 0:
        problems.append(f"timeout scale must be positive, got {settings.timeout_scale}")
    if not settings.rehearse and settings.approval not in NON_INTERACTIVE_APPROVALS:
        problems.append(
            f"approval mode {settings.approval!r} waits for a terminal answer, but the evaluation runs the agent with "
            f"stdin closed, so every approval would be denied; use one of {', '.join(sorted(NON_INTERACTIVE_APPROVALS))}"
        )
    if settings.rehearse:
        return problems
    if not settings.agent:
        problems.append(
            "HAIFA_LADDER_AGENT is not set and no packaged launcher was found "
            "(build one with scripts/package-local-coding-agent.ps1)"
        )
    elif not Path(settings.agent).is_file() and not shutil.which(settings.agent):
        problems.append(f"HAIFA_LADDER_AGENT does not resolve to an executable: {settings.agent}")
    if settings.agent:
        try:
            launcher_argv(settings.agent)
        except SystemExit as error:
            problems.append(str(error))
    variable, reason = credential_variable(settings.model, settings.credential_env)
    if variable and not os.environ.get(variable, "").strip():
        problems.append(f"{variable} is empty ({reason})")
    return problems


def setup_hint(settings: Settings) -> str:
    variable, _ = credential_variable(settings.model, settings.credential_env)
    credential = variable or "BIGMODEL_API_KEY"
    launcher = Path.home() / ".haifa-agent" / "coding" / ("haifa-coding.cmd" if os.name == "nt" else "haifa-coding")
    agent = settings.agent or str(launcher)
    windows = "\n".join(
        [
            "  # Windows PowerShell",
            '  $env:HAIFA_LADDER_ALLOW_REAL_PROVIDER = "true"',
            f'  $env:HAIFA_LADDER_AGENT = "{agent}"',
            '  $env:HAIFA_LADDER_MODEL = "glm-5.3-flash"   # optional, the agent configuration decides otherwise',
            f'  $env:{credential} = "<api key>"            # not needed for model-auth:// providers',
        ]
    )
    posix = "\n".join(
        [
            "  # bash / zsh",
            "  export HAIFA_LADDER_ALLOW_REAL_PROVIDER=true",
            f'  export HAIFA_LADDER_AGENT="{agent}"',
            "  export HAIFA_LADDER_MODEL=glm-5.3-flash",
            f'  export {credential}="<api key>"',
        ]
    )
    return "Set the missing variables and run the command again:\n\n" + windows + "\n\n" + posix


def describe_settings(settings: Settings) -> None:
    variable, reason = credential_variable(settings.model, settings.credential_env)
    state = "not required" if not variable else ("set" if os.environ.get(variable) else "MISSING")
    assets = str(settings.assets_dir) if settings.assets_dir else f"download per lock into {settings.cache_dir}"
    say("LADDER_SETTINGS")
    say(f"  action           : {settings.action}{' (rehearsal, no provider call)' if settings.rehearse else ''}")
    say(f"  agent            : {settings.agent or '-'}{launcher_note(settings)}")
    say(f"  model            : {settings.model or '(agent configuration default)'}")
    say(f"  credential       : {variable or '-'} [{state}] ({reason})")
    say(f"  approval         : {settings.approval}")
    say(f"  cases            : {', '.join(settings.case_patterns) if settings.case_patterns else 'all'}")
    say(f"  repeat           : {settings.repeat}    timeout scale: {settings.timeout_scale}")
    say(f"  assets           : {assets}")
    say(f"  report directory : {settings.output_dir}")
    say()


def launcher_note(settings: Settings) -> str:
    if not settings.agent or settings.rehearse:
        return ""
    try:
        _, note = launcher_argv(settings.agent)
    except SystemExit:
        return "  [batch launcher without a sibling JAR]"
    return f"  -> {note}"


def report_check(name: str, ok: bool, detail: str) -> None:
    say(f"  [{'OK  ' if ok else 'FAIL'}] {name:<16} {detail}")


def check_toolchain() -> tuple[bool, str]:
    missing = [tool for tool in ("git", "javac", "java") if shutil.which(tool) is None]
    if missing:
        return False, f"missing on PATH: {', '.join(missing)} (javac and java are required by L6-02)"
    return True, "git, javac and java are available"


def check_runner_tests() -> tuple[bool, str]:
    completed = subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", str(TOOLS_ROOT / "tests"), "-p", "test_*.py"],
        capture_output=True,
        timeout=600,
        **run_case.CHILD_TEXT,
    )
    tail = [line for line in (completed.stderr or "").splitlines() if line.strip()][-1:]
    return completed.returncode == 0, (tail[0] if tail else "no output")


def lock_mismatch(checkout: Path, lock: dict) -> str | None:
    """Return why ``checkout`` is not the locked asset set, or None when it matches.

    The manifest pins the case-tree digest and ``verify_assets_root`` already checked the tree
    against it, so an equal manifest digest means the cases are byte-identical to the locked
    revision. Anything else produces results that cannot be compared with the pinned baseline.
    """
    digest = fetch_assets.sha256_file(checkout / run_case.ASSET_MANIFEST_NAME)
    if digest == lock["manifestSha256"]:
        return None
    return (
        f"manifest digest {digest[:12]} does not match assets.lock.json {lock['manifestSha256'][:12]}: "
        "this is not the pinned asset set"
    )


def resolve_assets(settings: Settings) -> tuple[bool, str, Path | None]:
    try:
        lock = fetch_assets.load_lock()
    except SystemExit as error:
        return False, str(error), None
    if settings.assets_dir:
        checkout = settings.assets_dir.resolve()
        try:
            run_case.verify_assets_root(checkout)
        except SystemExit as error:
            return False, str(error), None
        mismatch = lock_mismatch(checkout, lock)
        if mismatch is None:
            return True, f"locked revision at {checkout}", checkout
        if settings.allow_unpinned_assets:
            return True, f"UNPINNED {checkout}: {mismatch}", checkout
        return False, f"{mismatch} (use --allow-unpinned-assets to evaluate it on purpose)", None
    try:
        checkout = fetch_assets.materialize(settings.cache_dir.resolve(), lock)
    except SystemExit as error:
        return False, str(error), None
    return True, f"locked revision at {checkout}", checkout


def select_cases(cases_root: Path, patterns: list[str]) -> list[str]:
    available = sorted(path.name for path in cases_root.glob("L*-*") if path.is_dir())
    if not patterns:
        return available
    return [case_id for case_id in available if any(fnmatch.fnmatchcase(case_id, pattern) for pattern in patterns)]


def run_gate(cases_root: Path, mode: str, case_ids: list[str], repeat: int) -> tuple[bool, str]:
    """Run the NOP or oracle gate over the selected cases through the single-case runner."""
    arguments = argparse.Namespace(
        mode=mode,
        agent_command=None,
        repeat=repeat,
        timeout_seconds=None,
        acceptance_timeout=run_case.DEFAULT_ACCEPTANCE_TIMEOUT_SECONDS,
        work_root=None,
        keep_workdir=False,
    )
    unexpected: list[str] = []
    for index, case_id in enumerate(case_ids, start=1):
        verdicts = set()
        for attempt in range(1, repeat + 1):
            record = run_case.run_once(cases_root / case_id, arguments, attempt)
            verdicts.add(record["verdict"])
        ok = verdicts == {"OK"}
        if not ok:
            unexpected.append(f"{case_id}:{'/'.join(sorted(verdicts))}")
        say(f"    [{index:>2}/{len(case_ids)}] {mode:<6} {case_id} {'OK' if ok else 'UNEXPECTED'}")
    if unexpected:
        return False, f"{len(unexpected)} unexpected: {', '.join(unexpected[:5])}"
    return True, f"{len(case_ids) * repeat} runs behaved as expected"


def preflight(settings: Settings) -> tuple[bool, Path | None, list[str]]:
    """Run every preflight check; return (ok, asset checkout, selected case ids)."""
    say("LADDER_PREFLIGHT")
    problems = missing_environment(settings)
    report_check("environment", not problems, "; ".join(problems) or "every required variable is set")
    if problems:
        say()
        say(setup_hint(settings))
        return False, None, []

    ok_python = sys.version_info >= (3, 11)
    report_check("python", ok_python, f"{sys.version.split()[0]} (3.11 or newer required)")
    ok_toolchain, toolchain_detail = check_toolchain()
    report_check("toolchain", ok_toolchain, toolchain_detail)

    ok_assets, assets_detail, checkout = resolve_assets(settings)
    report_check("assets", ok_assets, assets_detail)
    if not ok_assets or checkout is None:
        say("LADDER_PREFLIGHT_RESULT FAIL")
        return False, None, []

    cases_root = checkout / "cases"
    case_ids = select_cases(cases_root, settings.case_patterns)
    report_check("case selection", bool(case_ids), f"{len(case_ids)} case(s): {', '.join(case_ids)}")

    ok_tests, tests_detail = check_runner_tests()
    report_check("runner tests", ok_tests, tests_detail)

    ok_gates = True
    if settings.skip_gates:
        report_check("gates", True, "skipped by --skip-gates")
    elif case_ids:
        for mode in ("nop", "oracle"):
            say(f"  running the {mode} gate over {len(case_ids)} case(s), {settings.gate_repeat} run(s) each")
            ok_gate, gate_detail = run_gate(cases_root, mode, case_ids, settings.gate_repeat)
            report_check(f"{mode} gate", ok_gate, gate_detail)
            ok_gates = ok_gates and ok_gate

    ok = all([ok_python, ok_toolchain, ok_assets, bool(case_ids), ok_tests, ok_gates])
    say(f"LADDER_PREFLIGHT_RESULT {'PASS' if ok else 'FAIL'}")
    say()
    return ok, checkout, case_ids


# ------------------------------------------------------------------------------------- evaluation


BATCH_SUFFIXES = (".cmd", ".bat")


def java_executable() -> str:
    """Resolve java the way the packaged launcher does."""
    home = os.environ.get("JAVA_HOME", "").strip()
    if home:
        candidate = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.is_file():
            return str(candidate)
    return shutil.which("java") or "java"


def launcher_argv(agent: str) -> tuple[list[str], str]:
    """Return the command prefix that starts the agent.

    A Windows batch launcher goes through cmd.exe, which cuts a multi-line argument at the first
    newline - the agent would silently receive a truncated task statement. When the packaged JAR
    sits next to such a launcher, call the JAR directly instead.
    """
    path = Path(agent)
    if path.suffix.lower() in BATCH_SUFFIXES:
        jar = path.with_name("haifa-agent.jar")
        configuration = path.with_name("haifa-coding.yaml")
        if jar.is_file() and configuration.is_file():
            return [java_executable(), "-jar", str(jar), "--config", str(configuration)], f"{jar.name} (bypassing {path.name})"
        raise SystemExit(
            f"{path.name} is a batch launcher and cmd.exe would truncate the multi-line task statement, "
            f"but {jar.name} is not next to it. Point HAIFA_LADDER_AGENT at the distribution JAR launcher "
            "produced by scripts/package-local-coding-agent.ps1."
        )
    return [agent], path.name


def prepare_launcher_environment(agent: str) -> list[str]:
    """Create the data directories and export the defaults the packaged launcher would set.

    Bypassing the batch launcher must not change where the run stores its database, transcripts
    and logs (see the launcher written by scripts/package-local-coding-agent.py).
    """
    path = Path(agent)
    if path.suffix.lower() not in BATCH_SUFFIXES:
        return []
    distribution = path.parent
    data = distribution / "data"
    (data / "transcripts").mkdir(parents=True, exist_ok=True)
    (distribution / "logs").mkdir(parents=True, exist_ok=True)
    defaults = {
        "HAIFA_SQLITE_DATABASE_PATH": str(data / "runtime.db"),
        "HAIFA_TRANSCRIPT_ROOT": str(data / "transcripts"),
        "HAIFA_LOG_DIR": str(distribution / "logs"),
    }
    applied = [name for name, value in defaults.items() if os.environ.setdefault(name, value) == value]
    return applied


def agent_argv(settings: Settings, workspace: Path, prompt: str, budget_seconds: int) -> list[str]:
    """Build the non-interactive one-shot command; the statement is passed as a single argument."""
    self_limit = max(60, int(budget_seconds * AGENT_SELF_LIMIT_RATIO))
    prefix, _ = launcher_argv(str(settings.agent))
    argv = [
        *prefix,
        "--workspace",
        str(workspace),
        "--approval",
        settings.approval,
        "--timeout",
        f"PT{self_limit}S",
    ]
    if settings.model:
        argv += ["--model", settings.model]
    return argv + ["-m", prompt]


def kill_process_tree(process: subprocess.Popen) -> None:
    if os.name == "nt":
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(process.pid)], capture_output=True)
    else:
        try:
            os.killpg(os.getpgid(process.pid), 9)
        except (ProcessLookupError, PermissionError):
            process.kill()
    try:
        process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        pass


def run_agent(settings: Settings, argv: list[str], workspace: Path, log_path: Path, budget_seconds: int, prefix: str) -> AgentOutcome:
    """Run the agent, stream its output into a log file and print a heartbeat while it works."""
    secrets = secret_values(settings)
    started = time.monotonic()
    state = {"lines": 0, "last": ""}
    creation = {"start_new_session": True} if os.name != "nt" else {}
    with log_path.open("w", encoding="utf-8", newline="\n") as log:
        process = subprocess.Popen(
            argv,
            cwd=workspace,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            env=os.environ.copy(),
            **creation,
        )

        def pump() -> None:
            assert process.stdout is not None
            for raw in process.stdout:
                line = redact(raw.rstrip("\n"), secrets)
                log.write(line + "\n")
                state["lines"] += 1
                if line.strip():
                    state["last"] = line.strip()

        reader = threading.Thread(target=pump, daemon=True)
        reader.start()

        timed_out = False
        next_heartbeat = started + HEARTBEAT_SECONDS
        while process.poll() is None:
            time.sleep(0.5)
            now = time.monotonic()
            if now - started > budget_seconds:
                timed_out = True
                kill_process_tree(process)
                break
            if now >= next_heartbeat:
                next_heartbeat = now + HEARTBEAT_SECONDS
                say(
                    f"{prefix} working {duration(now - started)}/{duration(budget_seconds)} "
                    f"lines={state['lines']} | {state['last'][:LAST_LINE_WIDTH]}"
                )
        reader.join(timeout=15)

    return AgentOutcome(
        exit_code=None if timed_out else process.returncode,
        lines=state["lines"],
        timed_out=timed_out,
        duration_seconds=time.monotonic() - started,
        last_line=state["last"][:LAST_LINE_WIDTH],
    )


def apply_reference(case_dir: Path, workspace: Path) -> None:
    reference = case_dir / "reference"
    for path in sorted(reference.rglob("*")):
        if path.is_file():
            target = workspace / path.relative_to(reference)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)


def normalized_result(result: dict) -> tuple[dict[str, bool], list[str]]:
    """Return the checks and failures of an acceptance result, safe to aggregate and to print.

    A contract violation is already recorded by ``result_contract_problems``; a malformed payload
    (checks as a list, failures holding numbers) must still not abort the whole ladder run.
    """
    raw_checks = result.get("checks")
    checks = (
        {str(name): bool(value) for name, value in raw_checks.items()} if isinstance(raw_checks, dict) else {}
    )
    raw_failures = result.get("failures")
    failures = [str(failure) for failure in raw_failures] if isinstance(raw_failures, list) else []
    return checks, failures


def evaluate_case(settings: Settings, case_dir: Path, attempt: int, prefix: str) -> tuple[CaseOutcome, dict]:
    """Prepare a workspace, let the agent work on it and grade the result."""
    metadata = run_case.case_metadata(case_dir)
    budget = int((metadata["timeoutSeconds"] or run_case.DEFAULT_AGENT_TIMEOUT_SECONDS) * settings.timeout_scale)
    workspace_root = settings.output_dir / "workspaces"
    workspace = workspace_root / f"{case_dir.name}-{attempt}"
    if workspace.exists():
        shutil.rmtree(workspace, ignore_errors=True)
    workspace.parent.mkdir(parents=True, exist_ok=True)
    run_case.copy_tree(case_dir / "base-workspace", workspace)

    started = time.monotonic()
    outcome: AgentOutcome
    if settings.rehearse:
        apply_reference(case_dir, workspace)
        outcome = AgentOutcome(exit_code=0, lines=0, timed_out=False, duration_seconds=0.0, last_line="rehearsal: reference applied")
    else:
        prompt = (case_dir / "prompt.txt").read_text(encoding="utf-8")
        log_path = settings.output_dir / "logs" / f"{case_dir.name}-{attempt}.log"
        log_path.parent.mkdir(parents=True, exist_ok=True)
        argv = agent_argv(settings, workspace, prompt, budget)
        say(f"{prefix} agent starts, budget {duration(budget)}, log {log_path.name}")
        outcome = run_agent(settings, argv, workspace, log_path, budget, prefix)

    if outcome.timed_out:
        result = {
            "schemaVersion": 1,
            "caseId": metadata["caseId"],
            "caseVersion": metadata["caseVersion"],
            "status": "INCOMPLETE_BUDGET",
            "passed": False,
            "checks": {"budget.agentCommand": False},
            "failures": [f"agent exceeded {budget}s"],
        }
        contract_problems: list[str] = []
        acceptance_stderr = ""
    else:
        exit_code, result, acceptance_stderr = run_case.run_acceptance(
            case_dir, workspace, run_case.DEFAULT_ACCEPTANCE_TIMEOUT_SECONDS
        )
        contract_problems = run_case.result_contract_problems(result, case_dir.name)
        if not isinstance(result, dict):
            result = {
                "schemaVersion": 1,
                "caseId": metadata["caseId"],
                "caseVersion": metadata["caseVersion"],
                "status": "FAILED",
                "passed": False,
                "checks": {"acceptance.produced": False},
                "failures": [f"acceptance exit {exit_code} without a result"],
            }

    checks, failures = normalized_result(result)
    status = result.get("status") if result.get("status") in RESULT_STATUSES else "FAILED"
    if contract_problems:
        status = "FAILED"
    duration_seconds = time.monotonic() - started
    record = {
        "caseId": case_dir.name,
        "level": metadata["level"],
        "mode": run_mode(settings),
        "attempt": attempt,
        "verdict": "OK" if result.get("passed") and not contract_problems else "UNEXPECTED",
        "accepted": bool(result.get("passed")) and not contract_problems,
        "expected": "PASSED",
        "status": status,
        "durationMillis": int(duration_seconds * 1000),
        "agentDurationMillis": int(outcome.duration_seconds * 1000),
        "agentExitCode": outcome.exit_code,
        "agentOutputLines": outcome.lines,
        "checks": checks,
        "failures": failures,
        "contractProblems": contract_problems,
    }
    if not settings.keep_workdir and not settings.rehearse and status == "PASSED":
        shutil.rmtree(workspace, ignore_errors=True)

    changed_sources, reasons = parse_diagnostics(acceptance_stderr)
    return (
        CaseOutcome(
            case_id=case_dir.name,
            level=metadata["level"],
            attempt=attempt,
            status=status,
            duration_seconds=duration_seconds,
            agent=outcome,
            checks_passed=sum(1 for value in checks.values() if value),
            checks_total=len(checks),
            failures=failures,
            changed_sources=changed_sources,
            reasons=reasons,
            detail="; ".join(contract_problems),
        ),
        record,
    )


def agent_summary(agent: AgentOutcome | None) -> str:
    if agent is None:
        return "agent -"
    if agent.last_line.startswith("rehearsal"):
        return "rehearsal"
    exit_code = "killed" if agent.exit_code is None else agent.exit_code
    return f"agent {duration(agent.duration_seconds)} exit={exit_code} lines={agent.lines}"


def print_case_result(
    prefix: str, outcome: CaseOutcome, tally: Counter, accepted: int, done: int, total: int, started: float
) -> None:
    indent = " " * len(prefix)
    changed = f"changed {len(outcome.changed_sources)}: {', '.join(outcome.changed_sources[:4])}" if outcome.changed_sources else "changed 0"
    say(
        f"{prefix} {outcome.status:<18} {duration(outcome.duration_seconds):>7} "
        f"checks {outcome.checks_passed}/{outcome.checks_total}  {agent_summary(outcome.agent)}  {changed}"
    )
    if outcome.failures:
        say(f"{indent} failed: {', '.join(outcome.failures[:6])}")
        for name in outcome.failures[:3]:
            reason = outcome.reasons.get(name)
            if reason:
                say(f"{indent}   {name}: {reason[:160]}")
    if outcome.detail:
        say(f"{indent} contract: {outcome.detail[:200]}")
    rate = f"{accepted * 100 // done}%" if done else "-"
    say(
        f"  progress {done}/{total}  PASSED {accepted} ({rate})  FAILED {tally['FAILED']}  "
        f"INCOMPLETE_BUDGET {tally['INCOMPLETE_BUDGET']}  elapsed {duration(time.monotonic() - started)}"
    )
    say()


def run_mode(settings: Settings) -> str:
    """The mode the report claims; a rehearsal must never look like a provider evaluation."""
    return "rehearse" if settings.rehearse else "agent"


def write_reports(settings: Settings, records: list[dict], cases_root: Path) -> Path:
    settings.output_dir.mkdir(parents=True, exist_ok=True)
    records_path = settings.output_dir / "run-records.jsonl"
    with records_path.open("w", encoding="utf-8", newline="\n") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=True, sort_keys=True) + "\n")
    arguments = argparse.Namespace(mode=run_mode(settings), repeat=settings.repeat)
    report = run_case.build_report(records, arguments, cases_root)
    report_path = settings.output_dir / "ladder-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=True, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    return report_path


def accepted_runs(records: list[dict]) -> int:
    """A run counts only when the acceptance result itself is valid and passed."""
    return sum(1 for record in records if record["accepted"])


def print_summary(records: list[dict], report_path: Path, started: float) -> None:
    tally = Counter(record["status"] for record in records)
    accepted = accepted_runs(records)
    levels: dict[str, list[int]] = {}
    for record in records:
        bucket = levels.setdefault(record["level"], [0, 0])
        bucket[0] += 1
        bucket[1] += 1 if record["accepted"] else 0
    say("LADDER_SUMMARY")
    say(
        f"  runs={len(records)} passed={accepted} failed={tally['FAILED']} "
        f"incompleteBudget={tally['INCOMPLETE_BUDGET']} wall={duration(time.monotonic() - started)}"
    )
    for level in sorted(levels):
        total, passed = levels[level]
        say(f"  {level}  {passed}/{total}  {passed * 100 // total if total else 0}%")
    not_accepted = [record["caseId"] for record in records if not record["accepted"]]
    if not_accepted:
        say(f"  not passed: {', '.join(not_accepted)}")
    contract = [record["caseId"] for record in records if record["contractProblems"]]
    if contract:
        say(f"  result contract violated by: {', '.join(contract)}")
    say(f"  report: {report_path}")


def evaluate(settings: Settings, checkout: Path, case_ids: list[str]) -> int:
    cases_root = checkout / "cases"
    settings.output_dir.mkdir(parents=True, exist_ok=True)
    total = len(case_ids) * settings.repeat
    started = time.monotonic()
    tally: Counter = Counter()
    records: list[dict] = []
    done = 0

    say(f"LADDER_RUN cases={len(case_ids)} repeat={settings.repeat} total={total}")
    if not settings.rehearse and settings.agent:
        applied = prepare_launcher_environment(settings.agent)
        if applied:
            say(f"  distribution data paths: {', '.join(applied)}")
    say()
    for case_id in case_ids:
        for attempt in range(1, settings.repeat + 1):
            done += 1
            suffix = f".{attempt}" if settings.repeat > 1 else ""
            prefix = f"  [{done:>2}/{total}] {case_id}{suffix}"
            metadata = run_case.case_metadata(cases_root / case_id)
            labels = metadata["labels"]
            variants = ",".join(metadata["variants"]) or "-"
            say(
                f"{prefix} start  labels={labels.get('localization')}/{labels.get('modificationSpan')}"
                f"/{labels.get('acceptance')} variants={variants}"
            )
            outcome, record = evaluate_case(settings, cases_root / case_id, attempt, prefix)
            tally[outcome.status] += 1
            records.append(record)
            print_case_result(prefix, outcome, tally, accepted_runs(records), done, total, started)

    report_path = write_reports(settings, records, cases_root)
    print_summary(records, report_path, started)
    return 0 if accepted_runs(records) == total else 1


# ------------------------------------------------------------------------------------------- main


def parse_arguments(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="run_ladder.py",
        description="Preflight the capability ladder and evaluate every case with the coding agent.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("action", choices=("check", "run"), help="check runs the preflight only")
    parser.add_argument("--agent", default=None, help="coding agent launcher (HAIFA_LADDER_AGENT)")
    parser.add_argument("--model", default=None, help="model id (HAIFA_LADDER_MODEL)")
    parser.add_argument("--credential-env", default=None, help="credential variable to verify")
    parser.add_argument("--approval", default=None, help="approval mode, default auto")
    parser.add_argument("--cases", default=None, help="comma separated case ids or glob patterns")
    parser.add_argument("--repeat", type=int, default=None, help="runs per case, default 1")
    parser.add_argument("--timeout-scale", type=float, default=None, help="multiplies the per-case budget")
    parser.add_argument("--output", default=None, help="report directory")
    parser.add_argument("--cache-dir", default=None, help="asset cache directory")
    parser.add_argument("--assets-dir", default=None, help="use an existing verified asset checkout")
    parser.add_argument("--skip-gates", action="store_true", help="skip the NOP and oracle gates")
    parser.add_argument("--gate-repeat", type=int, default=1, help="gate runs per case, default 1")
    parser.add_argument(
        "--rehearse",
        action="store_true",
        help="run the whole pipeline with the reference solution instead of the agent (no provider call)",
    )
    parser.add_argument("--keep-workdir", action="store_true", help="keep the workspace of passed cases")
    parser.add_argument(
        "--allow-unpinned-assets",
        action="store_true",
        help="accept an --assets-dir that does not match assets.lock.json (results are not comparable)",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    settings = resolve_settings(parse_arguments(argv))
    describe_settings(settings)
    ok, checkout, case_ids = preflight(settings)
    if not ok or checkout is None:
        return 2
    if settings.action == "check":
        say("LADDER_CHECK_ONLY the environment is ready; run the same command with `run` to evaluate")
        return 0
    return evaluate(settings, checkout, case_ids)


if __name__ == "__main__":
    raise SystemExit(main())
