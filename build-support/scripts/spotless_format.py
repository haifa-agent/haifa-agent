#!/usr/bin/env python3
"""Run Spotless formatting on changed or staged files targeting only affected submodules."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path, PurePosixPath

SPOTLESS_EXTENSIONS = {".java", ".xml", ".md", ".yml", ".yaml"}
IGNORED_ROOTS = {".git", "docs", "test-config", "local-tmp", "target", ".idea", ".vscode"}


def repository_root() -> Path:
    return Path(__file__).resolve().parents[2]


def direct_text(element: ET.Element, name: str) -> str | None:
    for child in element:
        if child.tag.rsplit("}", 1)[-1] == name and child.text:
            return child.text.strip()
    return None


def discover_modules(root: Path) -> dict[str, str]:
    """Return map of artifactId -> relative module directory path."""
    modules: dict[str, str] = {}
    for pom in root.rglob("pom.xml"):
        relative = pom.relative_to(root)
        if any(part in IGNORED_ROOTS for part in relative.parts[:-1]):
            continue
        try:
            project = ET.parse(pom).getroot()
        except (ET.ParseError, OSError):
            continue
        artifact_id = direct_text(project, "artifactId")
        if not artifact_id:
            continue
        module_path = relative.parent.as_posix()
        modules[artifact_id] = module_path
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


def get_git_files(root: Path, staged_only: bool) -> list[str]:
    """Get list of modified/added files from git."""
    command = ["git", "diff", "--name-only", "--diff-filter=ACMR", "-z"]
    if staged_only:
        command.append("--cached")
    try:
        result = subprocess.run(
            command,
            cwd=root,
            check=True,
            stdout=subprocess.PIPE,
        )
    except (subprocess.SubprocessError, OSError):
        return []

    files = [path for path in result.stdout.decode("utf-8", errors="replace").split("\0") if path]
    return files


def is_spotless_target(path: str) -> bool:
    posix_path = PurePosixPath(path.replace("\\", "/"))
    if any(part in IGNORED_ROOTS for part in posix_path.parts):
        return False
    return posix_path.suffix.lower() in SPOTLESS_EXTENSIONS


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--staged",
        action="store_true",
        help="Target only git staged files (default for pre-commit hook).",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Run spotless:check instead of spotless:apply.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = repository_root()
    changed_files = get_git_files(root, staged_only=args.staged)
    target_files = [path for path in changed_files if is_spotless_target(path)]

    if not target_files:
        print("[spotless] No matching files to format.")
        return 0

    modules = discover_modules(root)
    affected_modules: set[str] = set()
    has_root_files = False

    for file_path in target_files:
        artifact = module_for_path(file_path, modules)
        if artifact:
            affected_modules.add(artifact)
        else:
            has_root_files = True

    maven_cmd = root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    goal = "spotless:check" if args.check else "spotless:apply"
    maven_args = [str(maven_cmd), "--batch-mode", "--no-transfer-progress", goal]

    if not has_root_files and affected_modules:
        selectors = ",".join(f":{mod}" for mod in sorted(affected_modules))
        maven_args.extend(["-pl", selectors])
        print(f"[spotless] Running {goal} on {len(affected_modules)} module(s): {selectors}")
    else:
        print(f"[spotless] Running {goal} on root repository...")

    try:
        completed = subprocess.run(maven_args, cwd=root, check=False)
        if completed.returncode != 0:
            return completed.returncode
    except OSError as e:
        print(f"[spotless] Failed to invoke Maven wrapper: {e}", file=sys.stderr)
        return 1

    if args.staged and not args.check:
        # Re-stage any files that were modified by spotless
        stage_cmd = ["git", "add", "--", *target_files]
        try:
            subprocess.run(stage_cmd, cwd=root, check=True)
        except (subprocess.SubprocessError, OSError) as e:
            print(f"[spotless] Failed to re-stage formatted files: {e}", file=sys.stderr)
            return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
