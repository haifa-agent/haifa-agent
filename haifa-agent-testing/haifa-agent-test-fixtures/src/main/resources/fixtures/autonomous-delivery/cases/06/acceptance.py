#!/usr/bin/env python3

import hashlib
import json
import stat
import subprocess
import sys
import tempfile
from pathlib import Path
from zipfile import ZipFile, ZipInfo


CASE_ROOT = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run_cli(workspace, archive, destination):
    return subprocess.run(
        [sys.executable, "-m", "safezip", str(archive), str(destination)],
        cwd=workspace,
        capture_output=True,
        text=True,
        timeout=30,
    )


def main():
    workspace = Path(sys.argv[1]).resolve()
    checks = {}
    failures = []

    checks["testsUnchanged"] = digest(
        CASE_ROOT / "base-workspace/tests/test_safezip.py"
    ) == digest(workspace / "tests/test_safezip.py")
    tests = subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"],
        cwd=workspace,
        capture_output=True,
        text=True,
        timeout=60,
    )
    checks["visibleTests"] = tests.returncode == 0
    diff = subprocess.run(
        ["git", "diff", "--check"], cwd=workspace, capture_output=True, text=True
    )
    checks["diffCheck"] = diff.returncode == 0

    attacks = [
        ("parent", "../escaped.txt", b"owned", None),
        ("mixed", "safe\\..\\..\\escaped.txt", b"owned", None),
        ("absolute", "/tmp/safezip-owned.txt", b"owned", None),
        ("drive", "C:\\temp\\owned.txt", b"owned", None),
        ("unc", "\\\\server\\share\\owned.txt", b"owned", None),
    ]
    attack_results = []
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        keep_root = root / "existing"
        keep_root.mkdir()
        keep = keep_root / "keep.txt"
        keep.write_text("original", encoding="utf-8")
        for label, member, content, _ in attacks:
            archive = root / f"{label}.zip"
            with ZipFile(archive, "w") as target:
                target.writestr("partial/created.txt", "must-not-remain")
                target.writestr(member, content)
            destination = keep_root / label
            completed = run_cli(workspace, archive, destination)
            attack_results.append(
                completed.returncode != 0
                and not destination.exists()
                and keep.read_text("utf-8") == "original"
            )

        symlink_archive = root / "symlink.zip"
        link = ZipInfo("link")
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        with ZipFile(symlink_archive, "w") as target:
            target.writestr("partial.txt", "must-not-remain")
            target.writestr(link, "../outside")
        symlink_destination = keep_root / "symlink"
        symlink_result = run_cli(workspace, symlink_archive, symlink_destination)
        attack_results.append(
            symlink_result.returncode != 0 and not symlink_destination.exists()
        )

        safe_archive = root / "safe.zip"
        with ZipFile(safe_archive, "w") as target:
            target.writestr("nested/file.txt", "safe")
            target.writestr("资料/说明.txt", "安全")
        safe_destination = keep_root / "safe"
        safe_result = run_cli(workspace, safe_archive, safe_destination)
        checks["safeCompatibility"] = (
            safe_result.returncode == 0
            and (safe_destination / "nested/file.txt").read_text("utf-8") == "safe"
            and (safe_destination / "资料/说明.txt").read_text("utf-8") == "安全"
        )

    checks["attackRejection"] = all(attack_results)
    for name, passed in checks.items():
        if not passed:
            failures.append(name)
    print(json.dumps({"case": "06-python-security", "passed": not failures, "checks": checks, "failures": failures}, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
