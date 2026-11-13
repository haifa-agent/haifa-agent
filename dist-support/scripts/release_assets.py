"""Assemble the exact release asset set and verify uploaded draft assets."""

import argparse
import json
from pathlib import Path
import re
import shutil

from coding_installers import render_installer
from distribution_contracts import sha256_file


def assemble(tag: str, packages: Path, release: Path) -> None:
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", tag):
        raise ValueError(f"Invalid release tag: {tag}")
    archives = {
        "sdk": [f"haifa-agent-sdk-{tag}.zip"],
        "linux": [f"haifa-coding-agent-linux-x64-{tag}.tar.gz"],
        "windows": [
            f"haifa-coding-agent-windows-x64-{tag}.zip",
            f"haifa-personal-assistant-linux-x64-{tag}.zip",
            f"haifa-personal-assistant-windows-x64-{tag}.zip",
        ],
    }

    release.mkdir()
    checksums = []
    for platform, names in archives.items():
        source = packages / platform
        digests = {name: sha256_file(source / name) for name in names}
        expected = [f"{digest}  {name}" for name, digest in digests.items()]
        if sorted((source / "sha256sums.txt").read_text(encoding="utf-8").splitlines()) != sorted(expected):
            raise ValueError(f"Invalid {platform} checksum manifest")
        checksums.extend(expected)
        for name in names:
            shutil.copyfile(source / name, release / name)
            if name.startswith("haifa-coding-agent-"):
                installer = render_installer(release / name, release, digests[name])
                if installer.read_bytes() != (source / installer.name).read_bytes():
                    raise ValueError(f"Installer does not match the packaged archive: {installer.name}")
    (release / "sha256sums.txt").write_text("\n".join(sorted(checksums)) + "\n", encoding="utf-8", newline="\n")


def verify_draft(release: Path, metadata: dict) -> None:
    if not metadata["isDraft"]:
        raise ValueError("Expected an unpublished release")
    assets = metadata["assets"]
    uploaded = {asset["name"]: asset["digest"] for asset in assets if asset["state"] == "uploaded"}
    expected = {path.name: f"sha256:{sha256_file(path)}" for path in release.iterdir()}
    if len(assets) != len(expected) or uploaded != expected:
        missing = sorted(expected.keys() - uploaded.keys())
        extra = sorted(uploaded.keys() - expected.keys())
        wrong = sorted(name for name in expected.keys() & uploaded.keys() if expected[name] != uploaded[name])
        raise ValueError(f"Release asset mismatch. Missing: {missing}. Extra: {extra}. Wrong digest: {wrong}.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    assemble_parser = actions.add_parser("assemble")
    assemble_parser.add_argument("tag")
    assemble_parser.add_argument("packages", type=Path)
    assemble_parser.add_argument("release", type=Path)
    verify_parser = actions.add_parser("verify-draft")
    verify_parser.add_argument("release", type=Path)
    verify_parser.add_argument("metadata", type=Path)
    args = parser.parse_args()
    if args.action == "assemble":
        assemble(args.tag, args.packages, args.release)
    else:
        verify_draft(args.release, json.loads(args.metadata.read_text(encoding="utf-8")))


if __name__ == "__main__":
    main()
