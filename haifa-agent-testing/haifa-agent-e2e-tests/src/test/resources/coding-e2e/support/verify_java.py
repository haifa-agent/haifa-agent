#!/usr/bin/env python3

"""Cross-platform visible verifier for the small Coding E2E Java fixtures."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def executable(environment_name: str, fallback: str) -> str:
    configured = os.environ.get(environment_name, "").strip()
    if configured:
        return configured
    resolved = shutil.which(fallback)
    if resolved is None:
        raise RuntimeError(f"{environment_name} is unset and {fallback} is not on PATH")
    return resolved


def run(command: list[str], root: Path) -> None:
    completed = subprocess.run(
        command,
        cwd=root,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"verification command failed with exit status {completed.returncode}")


def main() -> int:
    root = Path(__file__).resolve().parent
    configuration = json.loads((root / "verify.json").read_text(encoding="utf-8"))
    for relative in configuration.get("requiredPaths", []):
        if not (root / relative).is_file():
            raise RuntimeError(f"required file is missing: {relative}")
    for relative in configuration.get("forbiddenPaths", []):
        if (root / relative).exists():
            raise RuntimeError(f"forbidden path still exists: {relative}")
    for relative, fragments in configuration.get("requiredText", {}).items():
        content = (root / relative).read_text(encoding="utf-8")
        for fragment in fragments:
            if fragment not in content:
                raise RuntimeError(f"required text is missing from {relative}")

    output_root = root / ".verify-out"
    output_root.mkdir(exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix="run-", dir=output_root))
    sources = [str(root / relative) for relative in configuration["sources"]]
    run(
        [executable("HAIFA_JAVAC_EXECUTABLE", "javac"), "--release", "21", "-d", str(output), *sources],
        root,
    )
    main_class = configuration.get("mainClass")
    if main_class:
        run(
            [executable("HAIFA_JAVA_EXECUTABLE", "java"), "-cp", str(output), main_class],
            root,
        )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as error:
        print(f"VERIFY_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
