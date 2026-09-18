#!/usr/bin/env python3
"""Materialize the exact autonomous-delivery asset revision declared by assets.lock.json.

This is an explicit network action. Maven tests and ``run_case.py`` never download assets.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from run_case import ASSET_MANIFEST_NAME, verify_assets_root

MODULE_ROOT = Path(__file__).resolve().parents[1]
LOCK_FILE = MODULE_ROOT / "assets.lock.json"


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(arguments: list[str], *, cwd: Path | None = None) -> str:
    completed = subprocess.run(
        ["git", *arguments], cwd=cwd, check=True, capture_output=True, text=True
    )
    return completed.stdout.strip()


def load_lock() -> dict:
    try:
        lock = json.loads(LOCK_FILE.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise SystemExit(f"missing asset lock: {LOCK_FILE}") from error
    except json.JSONDecodeError as error:
        raise SystemExit(f"invalid asset lock: {error.msg}") from error
    if not isinstance(lock, dict) or lock.get("schemaVersion") != 1:
        raise SystemExit("asset lock schemaVersion must be 1")
    repository = lock.get("repository")
    revision = lock.get("revision")
    manifest_sha256 = lock.get("manifestSha256")
    if not isinstance(repository, str) or not (
        repository.startswith("https://github.com/") or repository.startswith("git@github.com:")
    ):
        raise SystemExit("asset lock repository must be a GitHub HTTPS or SSH URL")
    if not isinstance(revision, str) or not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise SystemExit("asset lock revision must be a full lowercase Git commit SHA")
    if not isinstance(manifest_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", manifest_sha256):
        raise SystemExit("asset lock manifestSha256 must be a lowercase SHA-256 digest")
    return lock


def verify_checkout(checkout: Path, lock: dict) -> None:
    if git(["rev-parse", "HEAD"], cwd=checkout) != lock["revision"]:
        raise SystemExit("asset checkout HEAD does not match the locked revision")
    manifest = checkout / ASSET_MANIFEST_NAME
    if sha256_file(manifest) != lock["manifestSha256"]:
        raise SystemExit("asset manifest does not match the locked SHA-256 digest")
    verify_assets_root(checkout)


def materialize(cache_dir: Path, lock: dict) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    checkout = cache_dir / f"assets-{lock['revision']}"
    if checkout.exists():
        verify_checkout(checkout, lock)
        return checkout
    temporary = Path(tempfile.mkdtemp(prefix="assets-", dir=cache_dir))
    try:
        git(["clone", "--no-checkout", lock["repository"], str(temporary)])
        git(["checkout", "--detach", lock["revision"]], cwd=temporary)
        verify_checkout(temporary, lock)
        temporary.replace(checkout)
        return checkout
    except subprocess.CalledProcessError as error:
        detail = error.stderr.strip().splitlines()[-1] if error.stderr else "git failed"
        shutil.rmtree(temporary, ignore_errors=True)
        raise SystemExit(f"asset download failed: {detail}") from error
    except BaseException:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Fetch the locked autonomous-delivery assets.")
    parser.add_argument("--cache-dir", required=True, help="external cache directory for immutable asset checkouts")
    parser.add_argument("--print-path", action="store_true", help="print the verified checkout path")
    args = parser.parse_args(argv)
    checkout = materialize(Path(args.cache_dir).resolve(), load_lock())
    if args.print_path:
        print(checkout)
    else:
        print(f"ASSETS_READY revision={checkout.name.removeprefix('assets-')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
