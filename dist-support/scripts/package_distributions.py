#!/usr/bin/env python3
"""Haifa Agent Release Distribution Packager.

Builds and packages (release artifacts use a 'v'-prefixed version, e.g. v0.1.0):
1. haifa-coding-agent-windows-x64-v<version>.zip
2. haifa-personal-assistant-windows-x64-v<version>.zip
3. haifa-agent-sdk-v<version>.zip
4. sha256sums.txt
"""

import argparse
import hashlib
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile


def get_repo_root() -> Path:
    return Path(__file__).resolve().parent.parent.parent


def get_pom_version(repo_root: Path) -> str:
    pom_path = repo_root / "pom.xml"
    if not pom_path.exists():
        return "0.1.0-SNAPSHOT"
    try:
        tree = ET.parse(pom_path)
        root = tree.getroot()
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        version_elem = root.find("m:version", ns)
        if version_elem is not None and version_elem.text:
            return resolve_version_property(root, version_elem.text.strip(), ns)
    except Exception as exc:
        print(f"[WARN] Failed to parse pom.xml: {exc}")
    return "0.1.0-SNAPSHOT"


def resolve_version_property(root: ET.Element, version: str, ns: dict[str, str]) -> str:
    """Resolve a version placeholder such as `${revision}` from the POM properties."""
    placeholder = re.fullmatch(r"\$\{([^}]+)\}", version)
    if placeholder is None:
        return version
    properties = root.find("m:properties", ns)
    value = None if properties is None else properties.find(f"m:{placeholder.group(1)}", ns)
    if value is not None and value.text and value.text.strip():
        return value.text.strip()
    print(f"[WARN] Unresolved version property {version} in pom.xml")
    return version


def artifact_label(version: str) -> str:
    """Return the release artifact label: the version with a single 'v' prefix (e.g. v0.1.0)."""
    normalized = version.strip()
    if normalized[:1] in ("v", "V"):
        normalized = normalized[1:]
    if not normalized:
        raise ValueError("release version must not be blank")
    return f"v{normalized}"


def compute_sha256(file_path: Path) -> str:
    sha = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            sha.update(chunk)
    return sha.hexdigest()


def ensure_jre(repo_root: Path, work_dir: Path, force_rebuild: bool = False) -> Path:
    jre_dir = work_dir / "jre"
    java_exe = jre_dir / "bin" / ("java.exe" if os.name == "nt" else "java")
    if jre_dir.exists() and java_exe.exists() and not force_rebuild:
        print(f"[INFO] Using cached minimal JRE at: {jre_dir}")
        return jre_dir

    if jre_dir.exists():
        shutil.rmtree(jre_dir)

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


def find_jar(directory: Path, prefix: str) -> Path:
    candidates = [
        f
        for f in directory.glob(f"{prefix}*.jar")
        if not f.name.startswith("original-")
        and not f.name.endswith("-sources.jar")
        and not f.name.endswith("-javadoc.jar")
        and not f.name.endswith("-shaded.jar")
    ]
    if not candidates:
        # Fallback to shaded if only shaded exists
        shaded = [
            f
            for f in directory.glob(f"{prefix}*.jar")
            if not f.name.startswith("original-")
            and not f.name.endswith("-sources.jar")
            and not f.name.endswith("-javadoc.jar")
        ]
        if shaded:
            return shaded[0]
        raise FileNotFoundError(f"Could not find jar with prefix '{prefix}' in {directory}")
    return candidates[0]


def validate_model_configuration(content: str) -> None:
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


CA_CONFIG_SOURCE = (
    "haifa-agent-applications",
    "haifa-agent-cli",
    "distribution",
    "haifa-coding.yaml",
)

# Deployment overrides applied on top of the canonical CLI distribution template.
# Only values accepted by the CLI configuration validators may be used here.
# execution.maxTimeoutMillis stays within ExecutionLimits.MAXIMUM_ALLOWED_TIMEOUT (2 hours);
# runtime.maxWallTimeMillis keeps the one-shot task wall time at 30 minutes.
CA_SECTION_VALUE_OVERRIDES = {
    "execution": {"maxTimeoutMillis": "7200000"},
    "runtime": {"maxWallTimeMillis": "1800000"},
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


def package_ca(repo_root: Path, work_dir: Path, output_dir: Path, version: str, jre_dir: Path) -> Path:
    print("\n" + "=" * 70)
    print(f"[INFO] Packaging Coding Agent Windows Distribution (version: {version})...")
    print("=" * 70)

    cli_target_dir = repo_root / "haifa-agent-applications" / "haifa-agent-cli" / "target"
    cli_jar = find_jar(cli_target_dir, "haifa-agent-cli")
    print(f"[INFO] Found Coding Agent CLI JAR: {cli_jar.name}")

    label = artifact_label(version)
    stage_dir = work_dir / f"haifa-coding-agent-{label}"
    if stage_dir.exists():
        shutil.rmtree(stage_dir)
    stage_dir.mkdir(parents=True, exist_ok=True)

    # 1. Copy templates
    template_dir = repo_root / "dist-support" / "templates" / "ca"
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
    shutil.copytree(jre_dir, stage_dir / "jre")

    # 5. Create ZIP
    output_zip = output_dir / f"haifa-coding-agent-windows-x64-{label}.zip"
    print(f"[INFO] Compressing to {output_zip.name}...")
    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for file in stage_dir.rglob("*"):
            if file.is_file():
                rel_path = file.relative_to(stage_dir.parent)
                zf.write(file, rel_path)

    zip_size_mb = output_zip.stat().st_size / (1024 * 1024)
    print(f"[SUCCESS] Coding Agent package created: {output_zip} ({zip_size_mb:.2f} MB)")
    return output_zip


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


def package_pa(
    repo_root: Path, work_dir: Path, output_dir: Path, version: str, jre_dir: Path, skip_web_build: bool = False
) -> Path:
    print("\n" + "=" * 70)
    print(f"[INFO] Packaging Personal Assistant Windows Distribution (version: {version})...")
    print("=" * 70)

    web_dir = repo_root / "haifa-agent-applications" / "haifa-agent-personal-assistant-web"
    web_dist_dir = web_dir / "dist"

    if not skip_web_build or not (web_dist_dir / "index.html").exists():
        print("[INFO] Building Personal Assistant Web frontend (npm run build)...")
        npm_cmd = "npm.cmd" if os.name == "nt" else "npm"
        result = subprocess.run(
            [npm_cmd, "run", "build"],
            cwd=web_dir,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if result.returncode != 0:
            print(f"[ERROR] Failed to build PA Web frontend:\n{result.stderr}")
            sys.exit(result.returncode)
        print("[INFO] PA Web frontend built successfully.")

    server_target_dir = repo_root / "haifa-agent-applications" / "haifa-agent-personal-assistant-server" / "target"
    server_jar = find_jar(server_target_dir, "haifa-agent-personal-assistant-server")
    print(f"[INFO] Found Personal Assistant Server JAR: {server_jar.name}")

    label = artifact_label(version)
    stage_dir = work_dir / f"haifa-personal-assistant-{label}"
    if stage_dir.exists():
        shutil.rmtree(stage_dir)
    stage_dir.mkdir(parents=True, exist_ok=True)

    # 1. Copy templates
    template_dir = repo_root / "dist-support" / "templates" / "pa"
    shutil.copytree(template_dir / "bin", stage_dir / "bin")
    shutil.copy2(template_dir / "README.txt", stage_dir / "README.txt")

    # 2. Inject Web UI and copy lib
    lib_dir = stage_dir / "lib"
    lib_dir.mkdir(exist_ok=True)
    embedded_server_jar = lib_dir / server_jar.name
    inject_web_dist_into_server_jar(server_jar, web_dist_dir, embedded_server_jar)

    # 3. Copy JRE
    print("[INFO] Bundling minimal JRE into Personal Assistant package...")
    shutil.copytree(jre_dir, stage_dir / "jre")

    # 4. Create ZIP
    output_zip = output_dir / f"haifa-personal-assistant-windows-x64-{label}.zip"
    print(f"[INFO] Compressing to {output_zip.name}...")
    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for file in stage_dir.rglob("*"):
            if file.is_file():
                rel_path = file.relative_to(stage_dir.parent)
                zf.write(file, rel_path)

    zip_size_mb = output_zip.stat().st_size / (1024 * 1024)
    print(f"[SUCCESS] Personal Assistant package created: {output_zip} ({zip_size_mb:.2f} MB)")
    return output_zip


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

    libs_dir = stage_dir / "libs"
    libs_dir.mkdir(exist_ok=True)

    # Collect SDK artifacts
    sdk_modules = [
        repo_root / "haifa-agent-sdk" / "target",
        repo_root / "haifa-agent-sdk-starter" / "target",
        repo_root / "haifa-agent-spring" / "haifa-agent-spring-boot-starter" / "target",
        repo_root / "haifa-agent-spring" / "haifa-agent-spring-boot-autoconfigure" / "target",
    ]

    collected_count = 0
    for mod_dir in sdk_modules:
        if not mod_dir.exists():
            continue
        for jar in mod_dir.glob("*.jar"):
            if jar.name.startswith("original-"):
                continue
            shutil.copy2(jar, libs_dir / jar.name)
            collected_count += 1

    # BOM poms
    bom_paths = [
        repo_root / "build-support" / "haifa-agent-bom" / "pom.xml",
        repo_root / "build-support" / "haifa-agent-spring-bom" / "pom.xml",
    ]
    for bom in bom_paths:
        if bom.exists():
            dest_name = f"{bom.parent.name}-{version}.pom"
            shutil.copy2(bom, libs_dir / dest_name)
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
    for z in sorted(output_dir.glob("*.zip")):
        sha = compute_sha256(z)
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
        choices=["all", "ca", "pa", "sdk", "jre"],
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

    repo_root = get_repo_root()
    version = args.version or get_pom_version(repo_root)

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
    if args.action in ["all", "ca", "pa", "jre"]:
        jre_dir = ensure_jre(repo_root, work_dir, force_rebuild=not args.skip_jre)
        if args.action == "jre":
            return

    if args.action in ["all", "ca"]:
        package_ca(repo_root, work_dir, output_dir, version, jre_dir)

    if args.action in ["all", "pa"]:
        package_pa(repo_root, work_dir, output_dir, version, jre_dir, skip_web_build=args.skip_web_build)

    if args.action in ["all", "sdk"]:
        package_sdk(repo_root, work_dir, output_dir, version)

    if args.action == "all":
        write_checksums(output_dir)


if __name__ == "__main__":
    main()
