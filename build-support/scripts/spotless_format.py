#!/usr/bin/env python3
"""Run Spotless formatting or verification targeting only affected submodules."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path, PurePosixPath

SPOTLESS_EXTENSIONS = {".java", ".xml", ".md", ".yml", ".yaml"}
IGNORED_DIRS = {
    ".git",
    "docs",
    "test-config",
    "local-tmp",
    "target",
    "node_modules",
    ".idea",
    ".vscode",
    "examples",
    "sdk-consumer-smoke",
}


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


def file_to_spotless_pattern(root: Path, rel_path: str) -> str:
    """Convert repository-relative path to regex matching the file's absolute path in Spotless."""
    posix_path = PurePosixPath(rel_path.replace("\\", "/"))
    parts = [root.name] + list(posix_path.parts) if root.name else list(posix_path.parts)
    return ".*[\\\\/]" + "[\\\\/]".join(re.escape(p) for p in parts)


def parse_pre_push_stdin(stdin_text: str) -> list[tuple[str, str, str, str]]:
    """Parse git pre-push hook standard input lines: <local_ref> <local_sha> <remote_ref> <remote_sha>."""
    entries: list[tuple[str, str, str, str]] = []
    for line in stdin_text.strip().splitlines():
        parts = line.strip().split()
        if len(parts) == 4:
            entries.append((parts[0], parts[1], parts[2], parts[3]))
    return entries


def get_git_files_for_push(
    root: Path,
    stdin_text: str | None = None,
    is_git_hook: bool = False,
) -> list[str]:
    """Inspect files modified in commits being pushed."""
    # 1. If standard input from git pre-push hook is available, use exact ref/sha pairs
    if stdin_text is None and is_git_hook and not sys.stdin.isatty():
        try:
            stdin_text = sys.stdin.read()
        except OSError:
            stdin_text = ""

    if stdin_text:
        push_entries = parse_pre_push_stdin(stdin_text)
        if push_entries:
            all_files: set[str] = set()
            zero_sha = "0" * 40
            for _local_ref, local_sha, _remote_ref, remote_sha in push_entries:
                if not local_sha or local_sha == zero_sha or set(local_sha) == {"0"}:
                    # Deleting remote branch, no files to format
                    continue

                if not remote_sha or remote_sha == zero_sha or set(remote_sha) == {"0"}:
                    # New remote branch: inspect commits ahead of base branch
                    for base in ("origin/dev", "origin/main", "HEAD~1"):
                        try:
                            res = subprocess.run(
                                ["git", "diff", "--name-only", "--diff-filter=ACMR", "-z", f"{base}...{local_sha}"],
                                cwd=root,
                                stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL,
                                check=True,
                            )
                            diff_files = [p for p in res.stdout.decode("utf-8", errors="replace").split("\0") if p]
                            if diff_files:
                                all_files.update(diff_files)
                                break
                        except (subprocess.SubprocessError, OSError):
                            continue
                else:
                    # Existing remote branch: check whether push is fast-forward or force/rebase
                    try:
                        ancestor_check = subprocess.run(
                            ["git", "merge-base", "--is-ancestor", remote_sha, local_sha],
                            cwd=root,
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL,
                            check=False,
                        )
                        if ancestor_check.returncode == 0:
                            # Fast-forward push: diff only unpushed commits
                            res = subprocess.run(
                                ["git", "diff", "--name-only", "--diff-filter=ACMR", "-z", f"{remote_sha}..{local_sha}"],
                                cwd=root,
                                stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL,
                                check=True,
                            )
                            all_files.update(p for p in res.stdout.decode("utf-8", errors="replace").split("\0") if p)
                        else:
                            # Non-fast-forward / rebased force push:
                            # Compare against base branch to prevent diff explosion from diverged ancestors
                            matched_base = False
                            for base in ("origin/dev", "origin/main"):
                                try:
                                    res = subprocess.run(
                                        ["git", "diff", "--name-only", "--diff-filter=ACMR", "-z", f"{base}...{local_sha}"],
                                        cwd=root,
                                        stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL,
                                        check=True,
                                    )
                                    diff_files = [p for p in res.stdout.decode("utf-8", errors="replace").split("\0") if p]
                                    if diff_files:
                                        all_files.update(diff_files)
                                        matched_base = True
                                        break
                                except (subprocess.SubprocessError, OSError):
                                    continue
                            if not matched_base:
                                res = subprocess.run(
                                    ["git", "diff", "--name-only", "--diff-filter=ACMR", "-z", f"{remote_sha}..{local_sha}"],
                                    cwd=root,
                                    stdout=subprocess.PIPE,
                                    stderr=subprocess.DEVNULL,
                                    check=True,
                                )
                                all_files.update(p for p in res.stdout.decode("utf-8", errors="replace").split("\0") if p)
                    except (subprocess.SubprocessError, OSError):
                        pass

            return sorted(all_files)

    # 2. Fallback if no stdin or stdin yielded no files (e.g. manual invocation)
    # Check upstream tracking branch if ancestor of HEAD
    try:
        upstream_res = subprocess.run(
            ["git", "rev-parse", "--symbolic-full-name", "@{u}"],
            cwd=root,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=True,
        )
        upstream = upstream_res.stdout.decode("utf-8", errors="replace").strip()
        if upstream:
            ancestor_check = subprocess.run(
                ["git", "merge-base", "--is-ancestor", "@{u}", "HEAD"],
                cwd=root,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
            if ancestor_check.returncode == 0:
                res = subprocess.run(
                    ["git", "diff", "--name-only", "--diff-filter=ACMR", "-z", "@{u}..HEAD"],
                    cwd=root,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL,
                    check=True,
                )
                return sorted(set(p for p in res.stdout.decode("utf-8", errors="replace").split("\0") if p))
    except (subprocess.SubprocessError, OSError):
        pass

    # 3. If new branch without upstream or diverged from upstream, compare against base branch
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
        changed_files = get_git_files_for_push(root, is_git_hook=bool(args.git_args))
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

    chunk_size = 15
    chunks = [target_files[i : i + chunk_size] for i in range(0, len(target_files), chunk_size)]

    for chunk_idx, chunk_files in enumerate(chunks):
        chunk_modules: set[str] = set()
        chunk_has_root = False
        for file_path in chunk_files:
            artifact = module_for_path(file_path, modules)
            if artifact and artifact != "haifa-agent-parent":
                chunk_modules.add(artifact)
            else:
                chunk_has_root = True
        if chunk_has_root:
            chunk_modules.add("haifa-agent-parent")

        selectors = ",".join(f":{mod}" for mod in sorted(chunk_modules))
        maven_args = [str(maven_cmd), "-o", "--batch-mode", "--no-transfer-progress"]
        maven_args.extend(["-pl", selectors, goal])
        patterns = [file_to_spotless_pattern(root, f) for f in chunk_files]
        maven_args.append(f"-DspotlessFiles={','.join(patterns)}")
        if (root / ".git").is_file():
            maven_args.append("-Dspotless.ratchetFrom=")

        if len(chunks) > 1:
            print(f"[spotless] [{chunk_idx + 1}/{len(chunks)}] Executing {goal} on {len(chunk_modules)} affected module(s) ({len(chunk_files)} target file(s)): {selectors}")
        else:
            print(f"[spotless] Executing {goal} on {len(chunk_modules)} affected module(s) ({len(chunk_files)} target file(s)): {selectors}")

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
