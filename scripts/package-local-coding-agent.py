#!/usr/bin/env python3

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Optional


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build a portable Haifa Coding Agent distribution."
    )
    parser.add_argument(
        "output_directory",
        nargs="?",
        help="Absolute output path or a path relative to the repository root.",
    )
    return parser.parse_args()


def resolve_output_directory(repository_directory: Path, value: Optional[str]) -> Path:
    if value is None:
        output_directory = Path.home() / ".haifa-agent" / "coding"
    else:
        if not value.strip():
            raise ValueError("Output directory must not be blank.")
        candidate = Path(value).expanduser()
        output_directory = candidate if candidate.is_absolute() else repository_directory / candidate

    output_directory = output_directory.resolve()
    if output_directory.parent == output_directory:
        raise ValueError("Refusing to use a filesystem root as the distribution directory.")
    if output_directory.exists() and not output_directory.is_dir():
        raise ValueError(
            f"Distribution path exists and is not a directory: {output_directory}"
        )
    return output_directory


def build_cli(repository_directory: Path) -> Path:
    wrapper_name = "mvnw.cmd" if os.name == "nt" else "mvnw"
    maven_wrapper = repository_directory / wrapper_name
    if not maven_wrapper.is_file():
        raise FileNotFoundError(f"Maven Wrapper is missing: {maven_wrapper}")

    print("Building the Haifa Coding Agent shaded JAR...", flush=True)
    subprocess.run(
        [
            str(maven_wrapper),
            "--batch-mode",
            "--no-transfer-progress",
            "-pl",
            ":haifa-agent-cli",
            "-am",
            "-DskipTests",
            "clean",
            "package",
        ],
        cwd=repository_directory,
        check=True,
    )

    target_directory = (
        repository_directory / "haifa-agent-applications" / "haifa-agent-cli" / "target"
    )
    jar_files = sorted(
        candidate
        for candidate in target_directory.glob("haifa-agent-cli-*.jar")
        if candidate.is_file()
        and not candidate.name.startswith("original-")
        and not candidate.name.endswith("-sources.jar")
        and not candidate.name.endswith("-javadoc.jar")
    )
    if not jar_files:
        raise FileNotFoundError(
            f"The shaded CLI JAR was not produced in {target_directory}."
        )
    if len(jar_files) > 1:
        raise RuntimeError(
            "More than one shaded CLI JAR was found; run the Maven clean package first."
        )
    return jar_files[0]


def write_windows_launcher(output_directory: Path) -> None:
    launcher = """@echo off
setlocal
set "HAIFA_DISTRIBUTION_DIR=%~dp0"
if not exist "%HAIFA_DISTRIBUTION_DIR%data\\transcripts" mkdir "%HAIFA_DISTRIBUTION_DIR%data\\transcripts"
if errorlevel 1 exit /b %ERRORLEVEL%
if not defined HAIFA_SQLITE_DATABASE_PATH set "HAIFA_SQLITE_DATABASE_PATH=%HAIFA_DISTRIBUTION_DIR%data\\runtime.db"
if not defined HAIFA_TRANSCRIPT_ROOT set "HAIFA_TRANSCRIPT_ROOT=%HAIFA_DISTRIBUTION_DIR%data\\transcripts"
set "HAIFA_JAVA_EXE=java.exe"
if defined JAVA_HOME if exist "%JAVA_HOME%\\bin\\java.exe" set "HAIFA_JAVA_EXE=%JAVA_HOME%\\bin\\java.exe"
"%HAIFA_JAVA_EXE%" -jar "%HAIFA_DISTRIBUTION_DIR%haifa-agent.jar" --config "%HAIFA_DISTRIBUTION_DIR%haifa-coding.yaml" %*
exit /b %ERRORLEVEL%
"""
    with (output_directory / "haifa-coding.cmd").open(
        "w", encoding="utf-8", newline="\r\n"
    ) as launcher_file:
        launcher_file.write(launcher)


def write_posix_launcher(output_directory: Path) -> None:
    launcher = """#!/bin/sh
set -eu
umask 077
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
data_dir="${script_dir}/data"
mkdir -p "${data_dir}/transcripts"
: "${HAIFA_SQLITE_DATABASE_PATH:=${data_dir}/runtime.db}"
: "${HAIFA_TRANSCRIPT_ROOT:=${data_dir}/transcripts}"
export HAIFA_SQLITE_DATABASE_PATH HAIFA_TRANSCRIPT_ROOT
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    java_executable="${JAVA_HOME}/bin/java"
else
    java_executable=java
fi
exec "$java_executable" -jar "${script_dir}/haifa-agent.jar" --config "${script_dir}/haifa-coding.yaml" "$@"
"""
    launcher_file = output_directory / "haifa-coding"
    with launcher_file.open("w", encoding="utf-8", newline="\n") as output:
        output.write(launcher)
    launcher_file.chmod(0o755)


def yaml_single_quoted(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def render_configuration(
    configuration_template: Path, output_file: Path, data_directory: Path
) -> None:
    database_token = "__HAIFA_SQLITE_DATABASE_PATH__"
    transcript_token = "__HAIFA_TRANSCRIPT_ROOT__"
    content = configuration_template.read_text(encoding="utf-8")
    for token in (database_token, transcript_token):
        if content.count(token) != 1:
            raise ValueError(
                f"Coding Agent configuration template must contain exactly one {token}."
            )
    rendered = content.replace(
        database_token,
        yaml_single_quoted(str((data_directory / "runtime.db").resolve())),
    ).replace(
        transcript_token,
        yaml_single_quoted(str((data_directory / "transcripts").resolve())),
    )
    output_file.write_text(rendered, encoding="utf-8", newline="\n")


def assemble_distribution(
    configuration_file: Path, output_directory: Path, jar_file: Path
) -> None:
    output_directory.mkdir(parents=True, exist_ok=True)
    data_directory = output_directory / "data"
    (data_directory / "transcripts").mkdir(parents=True, exist_ok=True)
    shutil.copy2(jar_file, output_directory / "haifa-agent.jar")
    render_configuration(
        configuration_file, output_directory / "haifa-coding.yaml", data_directory
    )
    if os.name == "nt":
        write_windows_launcher(output_directory)
    else:
        write_posix_launcher(output_directory)
        (output_directory / "haifa-agent.jar").chmod(0o644)
        (output_directory / "haifa-coding.yaml").chmod(0o644)


def print_next_steps(output_directory: Path) -> None:
    print()
    print(f"Haifa Coding Agent distribution: {output_directory}")
    if os.name == "nt":
        print("Add it to PATH for the current PowerShell session:")
        print(f"  $env:Path = '{output_directory};' + $env:Path")
        print("Set the model credential, enter any workspace, and launch:")
        print("  $env:DEEPSEEK_API_KEY = '<secret>'")
        print(r"  Set-Location D:\path\to\project")
    else:
        print("Add it to PATH:")
        print(f'  export PATH="{output_directory}:$PATH"')
        print("Set the model credential, enter any workspace, and launch:")
        print("  export DEEPSEEK_API_KEY='<secret>'")
        print("  cd /path/to/project")
    print("  haifa-coding")


def main() -> int:
    arguments = parse_arguments()
    script_directory = Path(__file__).resolve().parent
    repository_directory = script_directory.parent
    output_directory = resolve_output_directory(
        repository_directory, arguments.output_directory
    )

    configuration_file = (
        repository_directory
        / "haifa-agent-applications"
        / "haifa-agent-cli"
        / "distribution"
        / "haifa-coding.yaml"
    )
    if not configuration_file.is_file():
        raise FileNotFoundError(
            f"Coding Agent default configuration is missing: {configuration_file}"
        )

    jar_file = build_cli(repository_directory)
    assemble_distribution(configuration_file, output_directory, jar_file)
    print_next_steps(output_directory)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
