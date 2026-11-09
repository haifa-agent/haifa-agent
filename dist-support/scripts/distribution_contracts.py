#!/usr/bin/env python3
"""Contracts shared by the Haifa Agent distribution and installation scripts.

The release packager (``dist-support/scripts``), the local portable packager and the
Coding Agent updater (``scripts``) must agree on the root POM version, the model
configuration contract, the shaded CLI JAR and the Maven entry point that builds it.
Those contracts live here once so the three commands cannot drift apart; each script
keeps only its own artifacts, layout and installation behaviour.
"""

import hashlib
import os
from pathlib import Path
import re
import shutil
import zipfile
import xml.etree.ElementTree as ET


POM_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
VERSION_PLACEHOLDER = re.compile(r"\$\{([^}]+)\}")
VERSION_PATTERN = re.compile(r"[0-9][A-Za-z0-9_.-]*")

# Layers, module selection and test-skip property of the repository Maven entry point.
# The Coding Agent build must never be a bare `mvnw` call: the layered wrapper owns
# concurrency, timeouts and redacted metrics for every repository build.
CLI_BUILD_LAYER = "L2"
CLI_BUILD_ARGUMENTS = ("-pl", ":haifa-agent-cli", "-am", "-DskipUnitTests=true", "clean", "package")

# The portable launcher runs the JAR with `java -jar`, so packaging must refuse a thin
# JAR produced by a shade-skipping build profile.
REQUIRED_SHADED_JAR_ENTRIES = (
    "io/haifa/agent/cli/HaifaCliMain.class",
    "com/williamcallahan/tui4j/compat/bubbletea/ProgramCore.class",
    "com/williamcallahan/tui4j/compat/bubbletea/ProgramCleanup.class",
)
SHADED_JAR_MAIN_CLASS = "io.haifa.agent.cli.HaifaCliMain"


def pom_version(content: str) -> str:
    """Return the root POM version, resolving a ``${revision}`` style placeholder.

    The version is also the distribution directory name, so unsupported values are
    rejected instead of being interpolated into a path.
    """
    root = ET.fromstring(content)
    version = (root.findtext("m:version", namespaces=POM_NAMESPACE) or "").strip()
    placeholder = VERSION_PLACEHOLDER.fullmatch(version)
    if placeholder is not None:
        version = (
            root.findtext(f"m:properties/m:{placeholder.group(1)}", namespaces=POM_NAMESPACE) or ""
        ).strip()
    if not VERSION_PATTERN.fullmatch(version):
        raise ValueError(f"Unsupported root POM version: {version!r}")
    return version


def read_pom_version(pom_path: Path) -> str:
    """Return the version of the given POM file."""
    return pom_version(pom_path.read_text(encoding="utf-8"))


def sha256_file(file_path: Path) -> str:
    """Return the lowercase SHA-256 digest of a file."""
    with file_path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_shaded_jar(jar_file: Path) -> None:
    """Reject a CLI JAR that cannot serve as the packaged Coding Agent runtime."""
    try:
        with zipfile.ZipFile(jar_file) as archive:
            entries = set(archive.namelist())
            manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="strict")
    except (OSError, KeyError, UnicodeError, zipfile.BadZipFile) as error:
        raise RuntimeError(f"The shaded CLI JAR is invalid: {jar_file}") from error
    missing = [entry for entry in REQUIRED_SHADED_JAR_ENTRIES if entry not in entries]
    if missing:
        raise RuntimeError(
            "The shaded CLI JAR is missing required runtime classes: "
            + ", ".join(missing)
        )
    if f"Main-Class: {SHADED_JAR_MAIN_CLASS}" not in manifest:
        raise RuntimeError("The shaded CLI JAR has an invalid Main-Class manifest")


def validate_model_configuration(content: str) -> None:
    """Reject Coding Agent configuration that does not match the current model API."""
    required_fragments = (
        "allowedBindings:",
        "deepseek-responses-flash",
        "deepseek-chat-pro",
        "model-auth://deepseek/default",
    )
    missing = [fragment for fragment in required_fragments if fragment not in content]
    if missing:
        raise ValueError(
            "Coding Agent configuration template is missing the current model API structure: "
            + ", ".join(missing)
        )
    forbidden_fragments = (
        "apiBindings:",
        "dialectId:",
        "dialectVersion:",
        "styleVersion:",
        "adapterType:",
        "CHATGPT2API_",
    )
    present = [fragment for fragment in forbidden_fragments if fragment in content]
    if present:
        raise ValueError(
            "Coding Agent configuration template contains retired model configuration: "
            + ", ".join(present)
        )


def cli_build_command(repository_directory: Path) -> list[str]:
    """Return the resolved command that builds the shaded Coding Agent CLI JAR."""
    scripts_directory = repository_directory / "build-support" / "scripts"
    if os.name == "nt":
        entry = scripts_directory / "invoke-haifa-maven.ps1"
        shell = shutil.which("pwsh") or shutil.which("powershell")
        if not shell:
            raise RuntimeError("PowerShell is required to build the Coding Agent CLI")
        prefix = [shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(entry)]
    else:
        entry = scripts_directory / "invoke-haifa-maven.sh"
        prefix = ["sh", str(entry)]
    if not entry.is_file():
        raise FileNotFoundError(f"The repository Maven entry point is missing: {entry}")
    return [*prefix, "--layer", CLI_BUILD_LAYER, "--", *CLI_BUILD_ARGUMENTS]
