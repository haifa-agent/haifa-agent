#!/usr/bin/env python3
"""Haifa Agent Release Distribution Packager.

Builds and packages (release artifacts use a 'v'-prefixed version, e.g. v0.1.0):
1. haifa-coding-agent-windows-x64-v<version>.zip
   haifa-coding-agent-linux-x64-v<version>.tar.gz (ca-linux, built on Linux x64)
2. haifa-personal-assistant-windows-x64-v<version>.zip
3. haifa-personal-assistant-server-linux-x64-v<version>.zip
4. haifa-agent-sdk-v<version>.zip
5. sha256sums.txt
"""

import argparse
import os
import platform
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import xml.etree.ElementTree as ET
import zipfile


# The three Coding Agent commands share one contracts module; keep the import usable
# when this file is loaded by path (tests) instead of being run as a script.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from distribution_contracts import (  # noqa: E402 - repository scripts run unpackaged
    read_pom_version,
    sha256_file,
    validate_model_configuration,
    validate_shaded_jar,
)


def get_repo_root() -> Path:
    return Path(__file__).resolve().parent.parent.parent


def get_pom_version(repo_root: Path) -> str:
    pom_path = repo_root / "pom.xml"
    if not pom_path.exists():
        return "0.1.0-SNAPSHOT"
    try:
        return read_pom_version(pom_path)
    except Exception as exc:
        print(f"[WARN] Failed to parse pom.xml: {exc}")
    return "0.1.0-SNAPSHOT"


def artifact_label(version: str) -> str:
    """Return the release artifact label: the version with a single 'v' prefix (e.g. v0.1.0)."""
    normalized = version.strip()
    if normalized[:1] in ("v", "V"):
        normalized = normalized[1:]
    if not normalized:
        raise ValueError("release version must not be blank")
    return f"v{normalized}"


def sdk_modules(repo_root: Path) -> list[tuple[Path, str, str]]:
    """Public Reactor artifacts, including their parent POMs; applications/testing are not SDKs."""
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    result = []

    def visit(directory: Path) -> None:
        pom = ET.parse(directory / "pom.xml").getroot()
        artifact = pom.findtext("m:artifactId", namespaces=ns)
        packaging = pom.findtext("m:packaging", default="jar", namespaces=ns)
        result.append((directory, artifact, packaging))
        for module in pom.findall("m:modules/m:module", ns):
            if directory == repo_root and module.text in ("haifa-agent-applications", "haifa-agent-testing"):
                continue
            visit(directory / module.text)

    visit(repo_root)
    return result


def native_runtime_platform() -> str:
    system = platform.system().lower()
    machine = platform.machine().lower()
    # Embedded Windows Python can have no PROCESSOR_ARCHITECTURE environment variable.
    # Validate the actual bundled runtime architecture from its binary header below.
    supported_machine = machine in ("amd64", "x86_64") or (system == "windows" and not machine)
    if system not in ("windows", "linux") or not supported_machine:
        raise ValueError("Bundled runtimes require a native Windows x64 or Linux x64 JDK 21")
    return f"{system}-x64"


def validate_jre(jre_dir: Path, target: str) -> None:
    java = jre_dir / "bin" / ("java.exe" if target == "windows-x64" else "java")
    with java.open("rb") as binary:
        header = binary.read(64)
        expected = b"MZ" if target == "windows-x64" else b"\x7fELF"
        if not header.startswith(expected):
            raise ValueError(f"Bundled Java runtime does not match {target}: {java}")
        if target == "windows-x64":
            binary.seek(int.from_bytes(header[60:64], "little"))
            x64 = binary.read(6) == b"PE\x00\x00\x64\x86"
        else:
            x64 = header[4:6] == b"\x02\x01" and int.from_bytes(header[18:20], "little") == 62
        if not x64:
            raise ValueError("Bundled runtime must use x64 architecture")
    release = (jre_dir / "release").read_text(encoding="utf-8")
    if not re.search(r'^JAVA_VERSION="21(?:[.\+"]|$)', release, re.MULTILINE):
        raise ValueError("Bundled runtime must use Java 21")


def ensure_jre(repo_root: Path, work_dir: Path, force_rebuild: bool = False) -> Path:
    target = native_runtime_platform()
    jre_dir = work_dir / "jre" / target
    java_exe = jre_dir / "bin" / ("java.exe" if target == "windows-x64" else "java")
    if jre_dir.exists() and java_exe.exists() and not force_rebuild:
        validate_jre(jre_dir, target)
        print(f"[INFO] Using cached minimal JRE at: {jre_dir}")
        return jre_dir

    if jre_dir.exists():
        shutil.rmtree(jre_dir)
    jre_dir.parent.mkdir(parents=True, exist_ok=True)

    print("[INFO] Generating minimal Java 21 JRE using jlink...")
    modules = [
        "java.base",
        "java.logging",
        "java.desktop",
        "java.management",
        "java.naming",
        "java.net.http",
        "java.security.jgss",
        "java.security.sasl",
        "java.sql",
        "java.xml",
        "java.instrument",
        "java.scripting",
        "jdk.unsupported",
        "jdk.crypto.ec",
        "jdk.httpserver",
        # ServiceLoader-provided RandomGenerator algorithms (L32X64MixRandom, ...) are not
        # reachable from java.base in the module graph, so jlink must include jdk.random explicitly.
        "jdk.random",
    ]

    cmd = [
        "jlink",
        "--no-header-files",
        "--no-man-pages",
        "--strip-debug",
        "--compress=zip-6",
        f"--add-modules={','.join(modules)}",
        f"--output={str(jre_dir)}",
    ]

    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        print(f"[ERROR] jlink execution failed:\nSTDOUT:\n{result.stdout}\nSTDERR:\n{result.stderr}")
        sys.exit(result.returncode)

    java_exe = jre_dir / "bin" / ("java.exe" if os.name == "nt" else "java")
    validate_jre(jre_dir, target)
    listed = subprocess.run(
        [str(java_exe), "--list-modules"], capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    available = {line.split("@", 1)[0].strip() for line in listed.stdout.splitlines() if line.strip()}
    missing = [module for module in modules if module not in available]
    if listed.returncode != 0 or missing:
        print(f"[ERROR] Minimal JRE is missing required modules: {', '.join(missing) or 'unknown'}")
        sys.exit(1)

    jre_size_mb = sum(f.stat().st_size for f in jre_dir.rglob("*") if f.is_file()) / (1024 * 1024)
    print(f"[INFO] Minimal JRE generated successfully! Size: {jre_size_mb:.2f} MB")
    return jre_dir


def find_jar(directory: Path, artifact: str, version: str) -> Path:
    candidate = directory / f"{artifact}-{version}.jar"
    if not candidate.is_file():
        raise FileNotFoundError(f"Build the exact release artifact before packaging: {candidate}")
    return candidate


CA_CONFIG_SOURCE = (
    "haifa-agent-applications",
    "haifa-agent-cli",
    "distribution",
    "haifa-coding.yaml",
)

# Deployment overrides applied on top of the canonical CLI distribution template.
# Only values accepted by the CLI configuration validators may be used here.
# execution.maxTimeoutMillis stays within ExecutionLimits.MAXIMUM_ALLOWED_TIMEOUT (2 hours);
# runtime.maxWallTimeMillis keeps the one-shot task wall time at 24 hours.
CA_SECTION_VALUE_OVERRIDES = {
    "execution": {"maxTimeoutMillis": "7200000"},
    "runtime": {"maxWallTimeMillis": "86400000"},
    "approval": {"threshold": "high"},
}

# `approval.mode` is omitted so the default ASK mode can pair with threshold=HIGH
# (the loader rejects an explicit ask plus a non-low threshold). Durable data paths
# are supplied by the launcher as HAIFA_* environment variables, not by the file.
CA_SECTION_REMOVED_KEYS = {
    "approval": {"mode"},
    "persistence": {"databasePath", "transcriptRoot"},
}


def render_ca_configuration(repo_root: Path, stage_dir: Path) -> None:
    source = repo_root.joinpath(*CA_CONFIG_SOURCE)
    if not source.is_file():
        raise FileNotFoundError(f"Coding Agent configuration template is missing: {source}")

    content = source.read_text(encoding="utf-8")
    validate_model_configuration(content)
    if "mode: SQLITE_WITH_JSONL" not in content:
        raise ValueError("Coding Agent configuration template must persist runs with SQLITE_WITH_JSONL")

    lines: list[str] = []
    section = None
    applied = {name: set() for name in CA_SECTION_VALUE_OVERRIDES}
    for line in content.splitlines():
        stripped = line.strip()
        if stripped and not line[0].isspace():
            section = stripped[:-1] if stripped.endswith(":") else None
            lines.append(line)
            continue
        key = stripped.split(":", 1)[0] if ":" in stripped else ""
        if section in CA_SECTION_REMOVED_KEYS and key in CA_SECTION_REMOVED_KEYS[section]:
            continue
        if section in CA_SECTION_VALUE_OVERRIDES and key in CA_SECTION_VALUE_OVERRIDES[section]:
            indent = line[: len(line) - len(line.lstrip())]
            lines.append(f"{indent}{key}: {CA_SECTION_VALUE_OVERRIDES[section][key]}")
            applied[section].add(key)
            continue
        lines.append(line)

    for name, values in CA_SECTION_VALUE_OVERRIDES.items():
        missing = set(values) - applied[name]
        if missing:
            raise ValueError(
                f"cannot apply Coding Agent configuration overrides for {name}: "
                + ", ".join(sorted(missing))
            )

    rendered = "\n".join(lines) + "\n"
    for token in ("__HAIFA_SQLITE_DATABASE_PATH__", "__HAIFA_TRANSCRIPT_ROOT__"):
        if token in rendered:
            raise ValueError(
                f"Coding Agent configuration must not hardcode {token}; "
                "the launcher supplies HAIFA_SQLITE_DATABASE_PATH and HAIFA_TRANSCRIPT_ROOT."
            )
    for key in ("databasePath", "transcriptRoot"):
        if f"\n  {key}:" in rendered:
            raise ValueError(
                f"Coding Agent configuration must not pin {key}; "
                "the launcher supplies the user-scoped data path."
            )
    (stage_dir / "haifa-coding.yaml").write_text(rendered, encoding="utf-8", newline="\n")


def package_ca(
    repo_root: Path, work_dir: Path, output_dir: Path, version: str, jre_dir: Path,
    target: str = "windows-x64",
) -> Path:
    if target not in ("windows-x64", "linux-x64"):
        raise ValueError(f"Unsupported Coding Agent platform: {target}")
    validate_jre(jre_dir, target)
    print("\n" + "=" * 70)
    print(f"[INFO] Packaging Coding Agent {target} Distribution (version: {version})...")
    print("=" * 70)

    cli_target_dir = repo_root / "haifa-agent-applications" / "haifa-agent-cli" / "target"
    cli_jar = find_jar(cli_target_dir, "haifa-agent-cli", version)
    validate_shaded_jar(cli_jar)
    print(f"[INFO] Found Coding Agent CLI JAR: {cli_jar.name}")

    label = artifact_label(version)
    stage_dir = stage_dir_for(work_dir, target, f"haifa-coding-agent-{label}")

    # 1. Copy templates
    template_dir = repo_root / "dist-support" / "templates" / ("ca-linux" if target == "linux-x64" else "ca")
    shutil.copytree(template_dir / "bin", stage_dir / "bin")
    shutil.copy2(template_dir / "README.txt", stage_dir / "README.txt")

    # 2. Render the secret-free default configuration (data paths come from the launcher)
    render_ca_configuration(repo_root, stage_dir)
    print("[INFO] Staged haifa-coding.yaml (persistence supplied via HAIFA_* launcher variables).")

    # 3. Copy lib
    lib_dir = stage_dir / "lib"
    lib_dir.mkdir(exist_ok=True)
    shutil.copy2(cli_jar, lib_dir / cli_jar.name)

    # 4. Copy JRE
    print("[INFO] Bundling minimal JRE into Coding Agent package...")
    shutil.copytree(jre_dir, stage_dir / "jre", symlinks=True)

    if target == "linux-x64":
        archive = output_dir / f"haifa-coding-agent-linux-x64-{label}.tar.gz"
        with tarfile.open(archive, "w:gz") as tar:
            tar.add(stage_dir, arcname=stage_dir.name, filter=linux_archive_permissions)
        print(f"[SUCCESS] Coding Agent package created: {archive}")
        return archive
    return zip_stage(stage_dir, output_dir / f"haifa-coding-agent-windows-x64-{label}.zip", "Coding Agent")


def linux_archive_permissions(info: tarfile.TarInfo) -> tarfile.TarInfo:
    # Set portable permissions even when WSL stages files on a Windows-mounted drive.
    relative = Path(info.name).parts[1:]
    executable = relative == ("bin", "haifa") or relative[:2] == ("jre", "bin") or relative == (
        "jre", "lib", "jspawnhelper")
    info.mode = 0o755 if info.isdir() or executable else 0o644
    return info


WEB_RELATIVE_API_BASE = "/api/v1"
WEB_RELATIVE_ADMIN_API_BASE = "/v1/admin"
WEB_ABSOLUTE_LOOPBACK_TOKEN = "http://127.0.0.1:20001/api/v1"


def verify_web_dist(web_dist_dir: Path, relative_api_base: bool) -> None:
    """Fail fast when the Web dist does not match the delivery mode of the target package.

    The Windows portable package is opened straight from the loopback Server, so its SPA must keep
    the absolute loopback base. The Linux server package is served by nginx, so its SPA must use the
    same-origin relative bases. Mixing the two yields a UI that silently calls the visitor's own
    machine, therefore a mismatch stops packaging instead of shipping it.
    """
    index = web_dist_dir / "index.html"
    if not index.is_file():
        raise FileNotFoundError(f"Web dist is incomplete: {index}")
    script_text = "".join(
        script.read_text(encoding="utf-8", errors="replace")
        for script in sorted((web_dist_dir / "assets").glob("*.js"))
    )
    has_loopback = WEB_ABSOLUTE_LOOPBACK_TOKEN in script_text
    if relative_api_base and has_loopback:
        raise ValueError(
            "Web dist still targets the loopback API; rebuild with "
            f"VITE_PERSONAL_ASSISTANT_API_BASE_URL={WEB_RELATIVE_API_BASE} for the server package"
        )
    if not relative_api_base and not has_loopback:
        raise ValueError(
            "Web dist does not target the loopback API; rebuild without VITE_* overrides "
            "for the Windows portable package"
        )
    if relative_api_base and WEB_RELATIVE_API_BASE not in script_text:
        raise ValueError(f"Web dist is missing the relative API base {WEB_RELATIVE_API_BASE}")
    if relative_api_base and WEB_RELATIVE_ADMIN_API_BASE not in script_text:
        raise ValueError(f"Web dist is missing the relative Admin API base {WEB_RELATIVE_ADMIN_API_BASE}")


def build_web_dist(repo_root: Path, skip_web_build: bool, relative_api_base: bool) -> Path:
    """Build (or reuse) personal-assistant-web/dist for one delivery mode and verify it."""
    web_dir = repo_root / "haifa-agent-applications" / "haifa-agent-personal-assistant-web"
    web_dist_dir = web_dir / "dist"

    if skip_web_build and (web_dist_dir / "index.html").is_file():
        print("[INFO] Reusing existing personal-assistant-web/dist")
    else:
        print("[INFO] Building Personal Assistant Web frontend (npm run build)...")
        environment = os.environ.copy()
        if relative_api_base:
            environment["VITE_PERSONAL_ASSISTANT_API_BASE_URL"] = WEB_RELATIVE_API_BASE
            environment["VITE_PERSONAL_ASSISTANT_ADMIN_API_BASE_URL"] = WEB_RELATIVE_ADMIN_API_BASE
        else:
            environment.pop("VITE_PERSONAL_ASSISTANT_API_BASE_URL", None)
            environment.pop("VITE_PERSONAL_ASSISTANT_ADMIN_API_BASE_URL", None)
        npm_cmd = "npm.cmd" if os.name == "nt" else "npm"
        result = subprocess.run(
            [npm_cmd, "run", "build"],
            cwd=web_dir,
            env=environment,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if result.returncode != 0:
            print(f"[ERROR] Failed to build PA Web frontend:\n{result.stderr}")
            sys.exit(result.returncode)
        print("[INFO] PA Web frontend built successfully.")

    verify_web_dist(web_dist_dir, relative_api_base)
    mode = "same-origin relative API" if relative_api_base else "loopback absolute API"
    print(f"[INFO] Web dist verified for {mode}")
    return web_dist_dir


def inject_web_dist_into_server_jar(source_jar: Path, web_dist_dir: Path, target_jar: Path) -> None:
    print(f"[INFO] Embedding Web UI into Personal Assistant Server JAR...")
    dist_files = {}
    for root, _, files in os.walk(web_dist_dir):
        for f in files:
            full_path = Path(root) / f
            rel_path = full_path.relative_to(web_dist_dir).as_posix()
            arcname = f"BOOT-INF/classes/static/{rel_path}"
            dist_files[arcname] = full_path

    with zipfile.ZipFile(source_jar, "r") as src_zip:
        with zipfile.ZipFile(target_jar, "w", compression=zipfile.ZIP_DEFLATED) as dst_zip:
            for item in src_zip.infolist():
                if item.filename in dist_files:
                    continue  # Will be replaced by newer web dist file
                data = src_zip.read(item.filename)
                dst_zip.writestr(item, data)

            for arcname, full_path in dist_files.items():
                dst_zip.write(full_path, arcname)

    print(f"[INFO] Embedded {len(dist_files)} static web assets into server JAR.")


PA_PACKAGE_NAME = "haifa-personal-assistant"


def pa_package_name(version: str) -> str:
    """Shared top-level directory name so both packages look identical inside the zip."""
    return f"{PA_PACKAGE_NAME}-{artifact_label(version)}"


def pa_artifact_name(platform: str, version: str) -> str:
    """Shared artifact shape: haifa-personal-assistant-<platform>-v<version>.zip."""
    return f"{PA_PACKAGE_NAME}-{platform}-{artifact_label(version)}.zip"


def stage_dir_for(work_dir: Path, profile: str, package_name: str) -> Path:
    """Per-profile staging root, so one invocation can build both packages without clobbering."""
    stage_dir = work_dir / profile / package_name
    if stage_dir.exists():
        shutil.rmtree(stage_dir)
    stage_dir.mkdir(parents=True, exist_ok=True)
    return stage_dir


def zip_stage(stage_dir: Path, output_zip: Path, description: str) -> Path:
    print(f"[INFO] Compressing to {output_zip.name}...")
    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for file in stage_dir.rglob("*"):
            if file.is_file():
                zf.write(file, file.relative_to(stage_dir.parent))
    zip_size_mb = output_zip.stat().st_size / (1024 * 1024)
    print(f"[SUCCESS] {description} package created: {output_zip} ({zip_size_mb:.2f} MB)")
    return output_zip


def package_pa(
    repo_root: Path, work_dir: Path, output_dir: Path, version: str, jre_dir: Path, skip_web_build: bool = False
) -> Path:
    print("\n" + "=" * 70)
    print(f"[INFO] Packaging Personal Assistant Windows Distribution (version: {version})...")
    print("=" * 70)

    web_dist_dir = build_web_dist(repo_root, skip_web_build=skip_web_build, relative_api_base=False)

    server_target_dir = repo_root / "haifa-agent-applications" / "haifa-agent-personal-assistant-server" / "target"
    server_jar = find_jar(server_target_dir, "haifa-agent-personal-assistant-server", version)
    print(f"[INFO] Found Personal Assistant Server JAR: {server_jar.name}")

    stage_dir = stage_dir_for(work_dir, "windows", pa_package_name(version))

    # 1. Copy templates (bin + conf, same layout the Linux package uses)
    template_dir = repo_root / "dist-support" / "templates" / "pa"
    for name in ("bin", "conf"):
        shutil.copytree(template_dir / name, stage_dir / name)
    shutil.copy2(template_dir / "README.txt", stage_dir / "README.txt")

    # 2. Windows is opened straight from loopback, so the SPA is embedded into app.jar
    inject_web_dist_into_server_jar(server_jar, web_dist_dir, stage_dir / "app.jar")

    # 3. Copy JRE
    print("[INFO] Bundling minimal JRE into Personal Assistant package...")
    shutil.copytree(jre_dir, stage_dir / "jre")

    # 4. Create ZIP
    return zip_stage(
        stage_dir,
        output_dir / pa_artifact_name("windows-x64", version),
        "Personal Assistant Windows",
    )


def package_pa_server(
    repo_root: Path, work_dir: Path, output_dir: Path, version: str, skip_web_build: bool = False
) -> Path:
    """Package the headless Linux server layout: system JDK, nginx-served SPA, systemd unit."""
    print("\n" + "=" * 70)
    print(f"[INFO] Packaging Personal Assistant Server Linux Distribution (version: {version})...")
    print("=" * 70)

    web_dist_dir = build_web_dist(repo_root, skip_web_build=skip_web_build, relative_api_base=True)

    server_target_dir = repo_root / "haifa-agent-applications" / "haifa-agent-personal-assistant-server" / "target"
    server_jar = find_jar(server_target_dir, "haifa-agent-personal-assistant-server", version)
    print(f"[INFO] Found Personal Assistant Server JAR: {server_jar.name}")

    with zipfile.ZipFile(server_jar) as jar:
        jar_entries = set(jar.namelist())
    if "BOOT-INF/classes/static/index.html" in jar_entries:
        raise ValueError(
            "Server JAR embeds the Web SPA; the Linux package serves it from nginx and must ship the plain JAR"
        )
    if not any(entry.startswith("BOOT-INF/lib/") for entry in jar_entries):
        raise ValueError("Server JAR is not a Spring Boot executable archive")

    stage_dir = stage_dir_for(work_dir, "linux", pa_package_name(version))

    template_dir = repo_root / "dist-support" / "templates" / "pa-server"
    for name in ("bin", "conf", "docker"):
        shutil.copytree(template_dir / name, stage_dir / name)
    shutil.copy2(template_dir / "README.txt", stage_dir / "README.txt")

    shutil.copy2(server_jar, stage_dir / "app.jar")
    shutil.copytree(web_dist_dir, stage_dir / "web")

    return zip_stage(
        stage_dir,
        output_dir / pa_artifact_name("linux-x64", version),
        "Personal Assistant Linux",
    )


def package_sdk(repo_root: Path, work_dir: Path, output_dir: Path, version: str) -> Path:
    print("\n" + "=" * 70)
    print(f"[INFO] Packaging SDK Release Bundle (version: {version})...")
    print("=" * 70)

    label = artifact_label(version)
    stage_dir = work_dir / f"haifa-agent-sdk-{label}"
    if stage_dir.exists():
        shutil.rmtree(stage_dir)
    stage_dir.mkdir(parents=True, exist_ok=True)

    template_dir = repo_root / "dist-support" / "templates" / "sdk"
    shutil.copy2(template_dir / "README.txt", stage_dir / "README.txt")

    repository = stage_dir / "repository" / "io" / "haifa"
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    collected_count = 0
    for directory, artifact, packaging in sdk_modules(repo_root):
        pom = directory / ".flattened-pom.xml"
        content = pom.read_text(encoding="utf-8")
        parsed = ET.fromstring(content)
        actual_version = parsed.findtext("m:version", namespaces=ns) or parsed.findtext(
            "m:parent/m:version", namespaces=ns
        )
        if "${revision}" in content or actual_version != version:
            raise ValueError(f"Rebuild {artifact}: expected flattened POM version {version}, got {actual_version}")
        destination = repository / artifact / version
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copy2(pom, destination / f"{artifact}-{version}.pom")
        collected_count += 1
        if packaging == "pom":
            continue
        suffixes = (".jar", "-sources.jar", "-javadoc.jar") if any(
            (directory / "src" / "main" / "java").rglob("*.java")
        ) else (".jar",)
        for suffix in suffixes:
            name = f"{artifact}-{version}{suffix}"
            shutil.copy2(directory / "target" / name, destination / name)
            collected_count += 1

    print(f"[INFO] Collected {collected_count} SDK artifacts.")

    output_zip = output_dir / f"haifa-agent-sdk-{label}.zip"
    print(f"[INFO] Compressing to {output_zip.name}...")
    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for file in stage_dir.rglob("*"):
            if file.is_file():
                rel_path = file.relative_to(stage_dir.parent)
                zf.write(file, rel_path)

    zip_size_mb = output_zip.stat().st_size / (1024 * 1024)
    print(f"[SUCCESS] SDK bundle created: {output_zip} ({zip_size_mb:.2f} MB)")
    return output_zip


def write_checksums(output_dir: Path) -> None:
    checksums_file = output_dir / "sha256sums.txt"
    lines = []
    for z in sorted([*output_dir.glob("*.zip"), *output_dir.glob("*.tar.gz")]):
        sha = sha256_file(z)
        lines.append(f"{sha}  {z.name}")

    with open(checksums_file, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    print("\n" + "=" * 70)
    print(f"[INFO] Generated SHA-256 Checksums ({checksums_file}):")
    for line in lines:
        print(f"  {line}")
    print("=" * 70)


def main():
    parser = argparse.ArgumentParser(description="Haifa Agent Release Distribution Packager")
    parser.add_argument(
        "action",
        nargs="?",
        default="all",
        choices=["all", "ca", "ca-linux", "pa", "pa-server", "sdk", "jre", "sdk-modules"],
        help="Target package to build (default: all)",
    )
    parser.add_argument("--version", dest="version", default=None, help="Explicit release version")
    parser.add_argument(
        "--skip-web-build",
        action="store_true",
        help="Reuse existing personal-assistant-web/dist without running npm build",
    )
    parser.add_argument("--skip-jre", action="store_true", help="Reuse existing cached JRE")
    parser.add_argument("--output-dir", dest="output_dir", default=None, help="Output directory")

    args = parser.parse_args()
    if args.action in ("all", "ca", "pa", "ca-linux"):
        required = "linux-x64" if args.action == "ca-linux" else "windows-x64"
        if native_runtime_platform() != required:
            parser.error(f"{args.action} requires a native {required} JDK 21; use ca-linux on Linux/WSL")

    repo_root = get_repo_root()
    version = args.version or get_pom_version(repo_root)
    if args.action == "sdk-modules":
        print(",".join(":" + artifact for _, artifact, _ in sdk_modules(repo_root)))
        return
    if version != get_pom_version(repo_root):
        raise ValueError("Release version must match the root POM revision; rebuild before packaging")

    dist_support_dir = repo_root / "dist-support"
    work_dir = dist_support_dir / "dist-work"
    output_dir = Path(args.output_dir) if args.output_dir else dist_support_dir / "output"

    work_dir.mkdir(parents=True, exist_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)

    print("=" * 70)
    print(f"Haifa Agent Release Distribution Builder")
    print(f"Version:      {version}")
    print(f"Artifacts:    {artifact_label(version)}")
    print(f"Action:       {args.action}")
    print(f"Output Dir:   {output_dir}")
    print(f"Work Dir:     {work_dir}")
    print("=" * 70)

    jre_dir = None
    if args.action in ["all", "ca", "ca-linux", "pa", "jre"]:
        jre_dir = ensure_jre(repo_root, work_dir, force_rebuild=not args.skip_jre)
        if args.action == "jre":
            return

    if args.action in ["all", "ca"]:
        package_ca(repo_root, work_dir, output_dir, version, jre_dir)

    if args.action == "ca-linux":
        package_ca(repo_root, work_dir, output_dir, version, jre_dir, target="linux-x64")

    if args.action in ["all", "pa"]:
        package_pa(repo_root, work_dir, output_dir, version, jre_dir, skip_web_build=args.skip_web_build)

    if args.action in ["all", "pa-server"]:
        package_pa_server(repo_root, work_dir, output_dir, version, skip_web_build=args.skip_web_build)

    if args.action in ["all", "sdk"]:
        package_sdk(repo_root, work_dir, output_dir, version)

    write_checksums(output_dir)


if __name__ == "__main__":
    main()
