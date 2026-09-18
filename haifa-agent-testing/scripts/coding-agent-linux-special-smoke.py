#!/usr/bin/env python3
"""Deterministic Coding Agent Linux filesystem, Git, process, and package smoke."""

import argparse
import hashlib
import json
import os
import platform
import stat
import subprocess
from pathlib import Path


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run deterministic Coding Agent Linux-special checks.")
    parser.add_argument("--run-root", required=True)
    parser.add_argument("--launcher", required=True)
    return parser.parse_args()


def run(argv: list[str], cwd: Path, environment: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        argv,
        cwd=cwd,
        env=environment,
        check=True,
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=30,
    )


def mode(path: Path) -> int:
    return stat.S_IMODE(path.stat().st_mode)


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    path.chmod(0o600)


def main() -> int:
    options = arguments()
    if platform.system() != "Linux":
        raise RuntimeError("this smoke is Linux-only")
    repository = Path(__file__).resolve().parents[2]
    run_root = Path(options.run_root).absolute()
    launcher = Path(options.launcher).resolve()
    distribution = launcher.parent
    if run_root.exists() or run_root == repository or repository in run_root.parents:
        raise ValueError("run root must be a new directory outside the repository")
    if not launcher.is_file() or not os.access(launcher, os.X_OK):
        raise ValueError("launcher must be executable")
    run_root.mkdir(mode=0o700, parents=False)

    checks: dict[str, bool] = {}
    checks["launcherMode0755"] = mode(launcher) == 0o755
    checks["jarMode0644"] = mode(distribution / "haifa-agent.jar") == 0o644
    checks["configurationMode0644"] = mode(distribution / "haifa-coding.yaml") == 0o644

    launch_workspace = run_root / "launch workspace 中文"
    launch_workspace.mkdir(mode=0o700)
    environment = dict(os.environ)
    environment["JAVA_HOME"] = "/usr/lib/jvm/java-21-openjdk-amd64"
    help_result = run([str(launcher), "--help"], launch_workspace, environment)
    checks["launcherFromUnicodeWorkingDirectory"] = help_result.returncode == 0 and "Usage" in help_result.stdout
    checks["launcherPreservesWorkingDirectory"] = not any(
        str(run_root) in value for value in (help_result.stdout, help_result.stderr)
    )

    filesystem = run_root / "filesystem"
    filesystem.mkdir(mode=0o700)
    (filesystem / "README.md").write_text("upper\n", encoding="utf-8")
    (filesystem / "Readme.md").write_text("mixed\n", encoding="utf-8")
    checks["caseSensitiveNamesCoexist"] = (
        (filesystem / "README.md").read_text(encoding="utf-8") == "upper\n"
        and (filesystem / "Readme.md").read_text(encoding="utf-8") == "mixed\n"
    )
    executable = filesystem / "hello-linux"
    executable.write_text("#!/bin/sh\nprintf 'shebang-ok\\n'\n", encoding="utf-8")
    executable.chmod(0o755)
    executable_result = run([str(executable)], filesystem)
    checks["executableBitAndShebang"] = executable_result.stdout == "shebang-ok\n"
    pending = filesystem / "atomic.pending"
    final = filesystem / "atomic.txt"
    pending.write_text("atomic-ok\n", encoding="utf-8")
    os.replace(pending, final)
    checks["atomicMove"] = final.read_text(encoding="utf-8") == "atomic-ok\n" and not pending.exists()

    process_workspace = run_root / "process-tree"
    process_workspace.mkdir(mode=0o700)
    process_result = run(
        [
            "/bin/bash",
            "-lc",
            "(/bin/bash -lc '(sleep 0.1; printf grandchild-ok > grandchild.txt) & wait'"
            " 'haifa-linux-child-marker') & wait; printf parent-ok",
        ],
        process_workspace,
    )
    checks["naturalProcessTreeCompletion"] = (
        process_result.stdout == "parent-ok"
        and (process_workspace / "grandchild.txt").read_text(encoding="utf-8") == "grandchild-ok"
    )

    git_workspace = run_root / "git-workspace"
    git_workspace.mkdir(mode=0o700)
    run(["git", "init", "--initial-branch=main"], git_workspace)
    run(["git", "config", "user.name", "Haifa Linux Test"], git_workspace)
    run(["git", "config", "user.email", "linux-test@invalid.local"], git_workspace)
    run(["git", "config", "core.fileMode", "true"], git_workspace)
    tracked = git_workspace / "run.sh"
    tracked.write_text("#!/bin/sh\nprintf baseline\n", encoding="utf-8")
    tracked.chmod(0o644)
    unrelated = git_workspace / "unrelated.txt"
    unrelated.write_text("preserve-me\n", encoding="utf-8")
    run(["git", "add", "run.sh", "unrelated.txt"], git_workspace)
    run(["git", "commit", "-m", "fixture: initialize linux workspace"], git_workspace)
    tracked.chmod(0o755)
    mode_diff = run(["git", "diff", "--summary"], git_workspace).stdout
    checks["gitExecutableModeDiff"] = "mode change 100644 => 100755 run.sh" in mode_diff
    tracked.write_text("#!/bin/sh\nprintf changed\n", encoding="utf-8")
    dirty_diff = run(["git", "diff", "--", "run.sh"], git_workspace).stdout
    checks["gitDirtyDiff"] = "printf changed" in dirty_diff
    checks["gitPreservesUnrelatedContent"] = unrelated.read_text(encoding="utf-8") == "preserve-me\n"
    run(["git", "restore", "run.sh"], git_workspace)
    worktree = run_root / "git worktree 中文"
    run(["git", "worktree", "add", "-b", "linux-special-worktree", str(worktree)], git_workspace)
    checks["gitWorktreeUsable"] = (worktree / "unrelated.txt").read_text(encoding="utf-8") == "preserve-me\n"
    run(["git", "worktree", "remove", str(worktree)], git_workspace)
    checks["gitWorktreeCleanRelease"] = not worktree.exists()

    passed = all(checks.values())
    result = {
        "schemaVersion": 1,
        "platform": "linux",
        "providerCalls": 0,
        "host": {
            "kernel": platform.release(),
            "machine": platform.machine(),
            "python": platform.python_version(),
            "git": run(["git", "--version"], run_root).stdout.strip(),
            "bash": run(["/bin/bash", "--version"], run_root).stdout.splitlines()[0],
            "java": run([environment["JAVA_HOME"] + "/bin/java", "-version"], run_root).stderr.splitlines()[0],
            "filesystem": run(["stat", "-f", "-c", "%T", str(run_root)], run_root).stdout.strip(),
        },
        "checks": checks,
        "passed": passed,
    }
    write_json(run_root / "result.json", result)
    (run_root / "launcher-help.txt").write_text(help_result.stdout, encoding="utf-8")
    (run_root / "launcher-help.txt").chmod(0o600)
    manifest = []
    for path in sorted(run_root.rglob("*")):
        if path.name == "manifest.sha256" or not path.is_file() or ".git" in path.parts:
            continue
        relative = path.relative_to(run_root)
        manifest.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {relative}")
    (run_root / "manifest.sha256").write_text("\n".join(manifest) + "\n", encoding="ascii")
    (run_root / "manifest.sha256").chmod(0o600)
    if not passed:
        failed = [name for name, value in checks.items() if not value]
        raise RuntimeError("Linux-special checks failed: " + ", ".join(failed))
    print(json.dumps({"passed": True, "checks": checks}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
