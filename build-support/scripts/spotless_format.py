#!/usr/bin/env python3
"""Run Spotless formatting or verification targeting only affected submodules."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path, PurePosixPath

SPOTLESS_EXTENSIONS = {".java", ".xml", ".md", ".yml", ".yaml"}
IGNORED_DIRS = {".git", "docs", "test-config", "local-tmp", "target", "node_modules", ".idea", ".vscode"}


def repository_root() -> Path:
    return Path(__file__).resolve().parents[2]


def direct_text(element: ET.Element, name: str) -> str | None:
    for child in element:
        if child.tag.rsplit("}", 1)[-1] == name and child.text:
            return child.text.strip()
    return None


def discover_modules(root: Path) -> dict[str, str]:
    """Fast discovery of Maven artifactId -> relative directory path."""
    modules: dict[str, str] = {}

    def scan_dir(current: Path, depth: int) -> None:
        if depth > 3:
            return
        pom = current / "pom.xml"
        if pom.is_file():
            try:
                project = ET.parse(pom).getroot()
                artifact_id = direct_text(project, "artifactId")
                if artifact_id:
                    rel = current.relative_to(root).as_posix()
                    modules[artifact_id] = rel
            except (ET.ParseError, OSError):
                pass
        for item in current.iterdir():
            if item.is_dir() and item.name not in IGNORED_DIRS and not item.name.startswith("."):
                scan_dir(item, depth + 1)

    scan_dir(root, 0)
    return modules


def module_for_path(path: str, modules: dict[str, str]) -> str | None:
    candidate = PurePosixPath(path.replace("\\", "/"))
    matches = [
        artifact_id
        for artifact_id, mod_path in modules.items()
        if mod_path != "." and (candidate == PurePosixPath(mod_path) or PurePosixPath(mod_path) in candidate.parents)
    ]
    if not matches:
        return None
    return max(matches, key=lambda art: len(PurePosixPath(modules[art]).parts))


def is_spotless_target(path: str) -> bool:
    posix_path = PurePosixPath(path.replace("\\", "/"))
    if any(part in IGNORED_DIRS for part in posix_path.parts):
        return False
    return posix_path.suffix.lower() in SPOTLESS_EXTENSIONS


def get_git_files_for_push(root: Path) -> list[str]:
    """Inspect files modified in commits being pushed."""
    # 1. If upstream tracking branch exists, diff only the unpushed commits
    try:
        res = subprocess.run(
            ["git", "diff", "--name-only", "--diff-filter=ACMR", "-z", "@{u}...HEAD"],
            cwd=root,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=True,
        )
        return sorted(set(p for p in res.stdout.decode("utf-8", errors="replace").split("\0") if p))
    except (subprocess.SubprocessError, OSError):
        pass

    # 2. If new branch without upstream, compare against base branch
    for base in ("origin/dev", "origin/main", "HEAD~1"):
        try:
            res = subprocess.run(
                ["git", "diff", "--name-only", "--diff-filter=ACMR", "-z", f"{base}...HEAD"],
                cwd=root,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                check=True,
            )
            return sorted(set(p for p in res.stdout.decode("utf-8", errors="replace").split("\0") if p))
        except (subprocess.SubprocessError, OSError):
            continue
    return []


def get_git_files_staged(root: Path) -> list[str]:
    try:
        res = subprocess.run(
            ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z"],
            cwd=root,
            check=True,
            stdout=subprocess.PIPE,
        )
        return [p for p in res.stdout.decode("utf-8", errors="replace").split("\0") if p]
    except (subprocess.SubprocessError, OSError):
        return []


def get_git_files_worktree(root: Path) -> list[str]:
    try:
        res = subprocess.run(
            ["git", "diff", "--name-only", "--diff-filter=ACMR", "-z", "HEAD"],
            cwd=root,
            check=True,
            stdout=subprocess.PIPE,
        )
        return [p for p in res.stdout.decode("utf-8", errors="replace").split("\0") if p]
    except (subprocess.SubprocessError, OSError):
        return []


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--push",
        action="store_true",
        help="Target files being pushed (default for pre-push hook).",
    )
    parser.add_argument(
        "--staged",
        action="store_true",
        help="Target git staged files.",
    )
    parser.add_argument(
        "--worktree",
        action="store_true",
        help="Target all modified files in working tree.",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Run spotless:check (verify without writing changes).",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Run spotless:apply (write formatting changes).",
    )
    parser.add_argument(
        "--amend",
        action="store_true",
        help="Automatically amend previous git commit with formatting changes after spotless:apply.",
    )
    parser.add_argument("git_args", nargs="*", help="Optional git hook arguments (e.g. remote name, remote url)")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = repository_root()

    if args.push:
        changed_files = get_git_files_for_push(root)
    elif args.staged:
        changed_files = get_git_files_staged(root)
    else:
        changed_files = get_git_files_worktree(root)

    target_files = [path for path in changed_files if is_spotless_target(path)]

    if not target_files:
        print("[spotless] No affected code files to format/check.")
        return 0

    modules = discover_modules(root)
    affected_modules: set[str] = set()
    has_root_files = False

    for file_path in target_files:
        artifact = module_for_path(file_path, modules)
        if artifact and artifact != "haifa-agent-parent":
            affected_modules.add(artifact)
        else:
            has_root_files = True

    maven_cmd = root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    if args.check:
        goal = "spotless:check"
    elif args.apply:
        goal = "spotless:apply"
    elif args.push:
        goal = "spotless:check"
    else:
        goal = "spotless:apply"

    maven_args = [str(maven_cmd), "-o", "--batch-mode", "--no-transfer-progress"]

    if has_root_files:
        affected_modules.add("haifa-agent-parent")

    if not affected_modules:
        print("[spotless] No affected code files to format/check.")
        return 0

    selectors = ",".join(f":{mod}" for mod in sorted(affected_modules))
    maven_args.extend(["-pl", selectors, goal])
    maven_args.append(f"-DspotlessFiles={','.join(target_files)}")
    print(f"[spotless] Executing {goal} on {len(affected_modules)} affected module(s) ({len(target_files)} target file(s)): {selectors}")

    try:
        completed = subprocess.run(maven_args, cwd=root, check=False)
        if completed.returncode != 0:
            if goal == "spotless:check":
                print(
                    "\n[spotless] ERROR: Unformatted code detected before push!\n"
                    "  To automatically format affected files and amend your commit, run:\n"
                    "    ./build-support/scripts/spotless-format.sh --push --apply --amend  (or .ps1 on Windows)\n"
                    "  Or run:\n"
                    "    ./build-support/scripts/spotless-format.sh --push --apply\n"
                    "    git commit -a --amend --no-edit\n",
                    file=sys.stderr,
                )
            return completed.returncode
    except OSError as e:
        print(f"[spotless] Failed to invoke Maven wrapper: {e}", file=sys.stderr)
        return 1

    if args.amend and goal == "spotless:apply":
        print("[spotless] Amending formatting changes into previous git commit...")
        try:
            subprocess.run(["git", "commit", "-a", "--amend", "--no-edit"], cwd=root, check=True)
            print("[spotless] Commit amended successfully.")
        except (subprocess.SubprocessError, OSError) as e:
            print(f"[spotless] Failed to amend commit: {e}", file=sys.stderr)
            return 1

    if args.staged and not args.check:
        stage_cmd = ["git", "add", "--", *target_files]
        try:
            subprocess.run(stage_cmd, cwd=root, check=True)
        except (subprocess.SubprocessError, OSError) as e:
            print(f"[spotless] Failed to re-stage formatted files: {e}", file=sys.stderr)
            return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
