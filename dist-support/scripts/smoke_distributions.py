#!/usr/bin/env python3
"""Exercise extracted distributions without a Provider request or user data."""
import argparse
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import socket
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import zipfile


def checksummed_archive(output: Path, checksums: dict[str, str], pattern: str) -> Path:
    """Return the single release archive of this shape, ignoring stale packages on disk."""
    matching = [output / name for name in sorted(checksums) if Path(name).match(pattern)]
    if len(matching) != 1:
        raise RuntimeError(f"Expected exactly one checksummed archive matching {pattern}")
    return matching[0]


def verify_ca_linux(output: Path, checksums: dict[str, str]) -> None:
    archive = checksummed_archive(output, checksums, "haifa-coding-agent-linux-x64-*.tar.gz")
    with tempfile.TemporaryDirectory(prefix="haifa-ca-linux-smoke-") as temporary:
        directory = Path(temporary)
        with tarfile.open(archive, "r:gz") as bundle:
            bundle.extractall(directory, filter="data")
        package = directory / archive.name.removesuffix(".tar.gz").replace("-linux-x64", "")
        environment = os.environ.copy()
        for key in ("HAIFA_CONFIG", "HAIFA_SQLITE_DATABASE_PATH", "HAIFA_TRANSCRIPT_ROOT", "HAIFA_LOG_DIR"):
            environment.pop(key, None)
        environment["HAIFA_DATA_ROOT"] = str(directory / "isolated data")
        subprocess.run([str(package / "bin/haifa"), "--help"], env=environment,
                       cwd=directory, check=True, stdout=subprocess.DEVNULL, timeout=60)
        modules = subprocess.check_output([str(package / "jre/bin/java"), "--list-modules"], text=True)
        if "java.base@21" not in modules or "jdk.random@21" not in modules:
            raise RuntimeError("Linux runtime is missing required Java 21 modules")
    print("PASS: Linux archive checksum, executable launcher and bundled Java 21 including jdk.random")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["verify", "verify-ca-linux"])
    parser.add_argument("--output-dir", type=Path, default=Path("dist-support/output"))
    args = parser.parse_args()
    if args.action == "verify-ca-linux" and not sys.platform.startswith("linux"):
        parser.error("verify-ca-linux must run on Linux/WSL")
    root = Path(__file__).resolve().parents[2]
    output = args.output_dir.resolve()
    checksums = {}
    for line in (output / "sha256sums.txt").read_text(encoding="utf-8").splitlines():
        digest, name = line.split(maxsplit=1)
        checksums[name] = digest
        with (output / name).open("rb") as source:
            if hashlib.file_digest(source, "sha256").hexdigest() != digest:
                raise RuntimeError(f"Release checksum mismatch: {name}")
    if args.action == "verify-ca-linux":
        verify_ca_linux(output, checksums)
        return
    work = root / "dist-support" / "dist-work"
    work.mkdir(exist_ok=True)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with tempfile.TemporaryDirectory(prefix="smoke-", dir=work) as temporary:
        directory = Path(temporary)
        packages = {}
        for product in ("coding-agent", "personal-assistant"):
            archive = checksummed_archive(output, checksums, f"haifa-{product}-windows-x64-*.zip")
            with zipfile.ZipFile(archive) as bundle:
                bundle.extractall(directory)
            packages[product] = directory / archive.stem.replace("-windows-x64", "")
        environment = os.environ.copy()
        environment.update({
            "HAIFA_DATA_ROOT": str(directory / "coding-data"),
            "HAIFA_PERSONAL_DATA_DIR": str(directory / "personal-data"),
            "HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED": "true",
            "HAIFA_CODEX_ORIGINATOR": "haifa",
            "HAIFA_CODEX_USER_AGENT": "haifa-agent/1",
        })
        ca = packages["coding-agent"]
        # The actual portable launcher checks its bundled configuration and JRE without changing PATH.
        subprocess.run([str(ca / "bin" / "haifa.bat"), "--help"], env=environment,
                       cwd=directory, check=True, stdout=subprocess.DEVNULL, timeout=60,
                       creationflags=subprocess.CREATE_NO_WINDOW)
        pa = packages["personal-assistant"]
        jar = pa / "app.jar"
        for attempt in (1, 2):
            with socket.socket() as listener:
                listener.bind(("127.0.0.1", 0))
                port = listener.getsockname()[1]
            log_path = output / f"personal-assistant-smoke-{attempt}.log"
            with log_path.open("wb") as log:
                process = subprocess.Popen(
                    [str(pa / "jre/bin/java.exe"), "-Dfile.encoding=UTF-8", "-jar", str(jar),
                     f"--server.port={port}", "--server.address=127.0.0.1"],
                    cwd=pa, env=environment, stdout=log, stderr=subprocess.STDOUT,
                    creationflags=subprocess.CREATE_NO_WINDOW,
                )
                try:
                    deadline = time.monotonic() + 90
                    base = f"http://127.0.0.1:{port}"
                    while True:
                        if process.poll() is not None:
                            raise RuntimeError(f"Personal Assistant exited; inspect {log_path}")
                        try:
                            with opener.open(base + "/actuator/health", timeout=2) as response:
                                if json.load(response)["status"] == "UP":
                                    break
                        except (urllib.error.URLError, TimeoutError):
                            pass
                        if time.monotonic() > deadline:
                            raise TimeoutError(f"Personal Assistant startup timed out; inspect {log_path}")
                        time.sleep(0.25)
                    with opener.open(base + "/index.html", timeout=5) as response:
                        if b"<html" not in response.read().lower():
                            raise RuntimeError("Packaged frontend is missing")
                    with opener.open(base + "/api/v1/memory", timeout=5) as response:
                        if json.load(response) != []:
                            raise RuntimeError("Smoke must use an empty isolated data directory")
                    databases = list((directory / "personal-data").rglob("*.sqlite"))
                    if not databases:
                        raise RuntimeError("Personal Assistant did not create a durable SQLite store")
                    for database in databases:
                        with closing(sqlite3.connect(database)) as connection:
                            if connection.execute("PRAGMA integrity_check").fetchone() != ("ok",):
                                raise RuntimeError("Packaged database failed its integrity check")
                finally:
                    if process.poll() is None:
                        process.terminate()
                    try:
                        process.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
        print("PASS: ZIP checksums, bundled CA launcher/JRE, PA health/frontend/Memory API, SQLite reopen")


if __name__ == "__main__":
    main()
