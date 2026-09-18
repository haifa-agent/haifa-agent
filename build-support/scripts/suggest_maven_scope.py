#!/usr/bin/env python3
"""Suggest a conservative Maven feedback scope without skipping final gates."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path, PurePosixPath


IGNORED_ROOTS = {".git", "docs", "test-config", "local-tmp", "target"}
HIGH_RISK_PREFIXES = (
    ".github/workflows/",
    ".mvn/",
    "build-support/",
    "haifa-agent-contract/",
    "haifa-agent-kernel/haifa-agent-common/",
    "haifa-agent-kernel/haifa-agent-core/",
    "haifa-agent-kernel/haifa-agent-runtime-",
    "haifa-agent-capabilities/haifa-agent-credential-",
    "haifa-agent-integrations/haifa-agent-store-sqlite/",
)


@dataclass(frozen=True)
class MavenModule:
    artifact_id: str
    path: str
    dependencies: tuple[str, ...]


def direct_text(element: ET.Element, name: str) -> str | None:
    for child in element:
        if child.tag.rsplit("}", 1)[-1] == name and child.text:
            return child.text.strip()
    return None


def discover_modules(root: Path) -> dict[str, MavenModule]:
    modules: dict[str, MavenModule] = {}
    for pom in root.rglob("pom.xml"):
        relative = pom.relative_to(root)
        if any(part in IGNORED_ROOTS for part in relative.parts[:-1]):
            continue
        project = ET.parse(pom).getroot()
        artifact_id = direct_text(project, "artifactId")
        if not artifact_id:
            continue
        dependencies: list[str] = []
        for child in project:
            if child.tag.rsplit("}", 1)[-1] != "dependencies":
                continue
            for dependency in child:
                if dependency.tag.rsplit("}", 1)[-1] != "dependency":
                    continue
                group_id = direct_text(dependency, "groupId")
                dependency_artifact = direct_text(dependency, "artifactId")
                if group_id == "io.haifa" and dependency_artifact:
                    dependencies.append(dependency_artifact)
        module_path = relative.parent.as_posix()
        modules[artifact_id] = MavenModule(artifact_id, module_path, tuple(sorted(set(dependencies))))
    internal = set(modules)
    return {
        artifact: MavenModule(module.artifact_id, module.path, tuple(d for d in module.dependencies if d in internal))
        for artifact, module in modules.items()
    }


def git_changed_files(root: Path, base: str, head: str | None) -> list[str]:
    revision_arguments = [base, head] if head else [base]
    result = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=ACMRTUXB", "-z", *revision_arguments],
        cwd=root,
        check=True,
        stdout=subprocess.PIPE,
    )
    changed = {path for path in result.stdout.decode("utf-8", errors="replace").split("\0") if path}
    if not head:
        untracked = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard", "-z"],
            cwd=root,
            check=True,
            stdout=subprocess.PIPE,
        )
        changed.update(
            path for path in untracked.stdout.decode("utf-8", errors="replace").split("\0") if path
        )
    return sorted(changed)


def module_for_path(path: str, modules: dict[str, MavenModule]) -> str | None:
    candidate = PurePosixPath(path.replace("\\", "/"))
    matches = [
        module
        for module in modules.values()
        if module.path != "." and (candidate == PurePosixPath(module.path) or PurePosixPath(module.path) in candidate.parents)
    ]
    if not matches:
        return None
    return max(matches, key=lambda module: len(PurePosixPath(module.path).parts)).artifact_id


def dependency_closure(seeds: set[str], modules: dict[str, MavenModule]) -> set[str]:
    closure = set(seeds)
    pending = list(seeds)
    while pending:
        current = pending.pop()
        for dependency in modules[current].dependencies:
            if dependency not in closure:
                closure.add(dependency)
                pending.append(dependency)
    return closure


def consumer_closure(seeds: set[str], modules: dict[str, MavenModule]) -> set[str]:
    reverse: dict[str, set[str]] = {artifact: set() for artifact in modules}
    for artifact, module in modules.items():
        for dependency in module.dependencies:
            reverse[dependency].add(artifact)
    closure: set[str] = set()
    pending = list(seeds)
    while pending:
        current = pending.pop()
        for consumer in reverse[current]:
            if consumer not in seeds and consumer not in closure:
                closure.add(consumer)
                pending.append(consumer)
    return closure


def risk_reasons(changed_files: list[str]) -> list[str]:
    reasons: set[str] = set()
    for raw in changed_files:
        path = raw.replace("\\", "/")
        if path == "pom.xml" or path.endswith("/pom.xml"):
            reasons.add("Maven model, plugin, profile, or dependency changed")
        if path == "mvnw" or path == "mvnw.cmd" or path.startswith(".mvn/"):
            reasons.add("Maven Wrapper or Maven runtime changed")
        if path.startswith(HIGH_RISK_PREFIXES):
            reasons.add("public API, runtime, persistence, security, build, or CI boundary changed")
        if "ArchitectureTest" in path or "ContractTest" in path:
            reasons.add("architecture or contract test selection changed")
        if path.endswith(('.yml', '.yaml')) and path.startswith(".github/workflows/"):
            reasons.add("CI gate selection changed")
    return sorted(reasons)


def build_recommendation(root: Path, base: str, head: str | None) -> dict[str, object]:
    modules = discover_modules(root)
    changed_files = git_changed_files(root, base, head)
    ignored = [path for path in changed_files if path.split("/", 1)[0] in {"docs", "test-config"}]
    root_files = [path for path in changed_files if module_for_path(path, modules) is None and path not in ignored]
    changed_modules = {artifact for path in changed_files if (artifact := module_for_path(path, modules))}
    upstream = dependency_closure(changed_modules, modules) - changed_modules if changed_modules else set()
    consumers = consumer_closure(changed_modules, modules) if changed_modules else set()
    risks = risk_reasons(changed_files)
    selected = sorted(changed_modules | consumers)
    selectors = ",".join(f":{artifact}" for artifact in selected)
    if risks or root_files:
        commands = {
            "l1": "Run targeted tests for the edited classes before the full gate.",
            "l2": "Run affected product sentinels; scope cannot be proven minimal for this change.",
            "final": ".\\build-support\\scripts\\invoke-haifa-maven.ps1 --layer L3 -- "
            "-Pci-fast clean verify",
        }
    elif selectors:
        commands = {
            "l1": f".\\mvnw.cmd -pl {selectors} -am test",
            "l2": f".\\build-support\\scripts\\invoke-haifa-maven.ps1 --layer L2 -- -pl {selectors} -am test",
            "final": ".\\build-support\\scripts\\invoke-haifa-maven.ps1 --layer L3 -- "
            "-Pci-fast clean verify",
        }
    else:
        commands = {
            "l1": "No Maven module change detected; run the relevant non-Maven validation.",
            "l2": "No Maven product scope suggested.",
            "final": "Use the normal final gate when the change is deliverable code or build configuration.",
        }
    return {
        "schemaVersion": 1,
        "base": base,
        "head": head or "WORKTREE",
        "advisoryOnly": True,
        "changedFiles": changed_files,
        "ignoredNestedRepositories": ignored,
        "directModules": sorted(changed_modules),
        "upstreamCompileDependencies": sorted(upstream),
        "downstreamConsumers": sorted(consumers),
        "rootOrUnmappedFiles": root_files,
        "highRiskReasons": risks,
        "suggestedCommands": commands,
        "moduleDetails": [asdict(modules[artifact]) for artifact in sorted(changed_modules | upstream | consumers)],
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="HEAD", help="Diff base; defaults to current HEAD")
    parser.add_argument("--head", default="", help="Optional committed diff head; default is the working tree")
    parser.add_argument("--pretty", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = Path(__file__).resolve().parents[2]
    try:
        result = build_recommendation(root, args.base, args.head or None)
    except (subprocess.CalledProcessError, ET.ParseError, OSError) as exception:
        print(f"Unable to suggest Maven scope: {exception}", file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2 if args.pretty else None, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
