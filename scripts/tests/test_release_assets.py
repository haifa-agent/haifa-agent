"""Release asset assembly and draft publication contracts."""

import importlib
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "dist-support" / "scripts"))
release_assets = importlib.import_module("release_assets")
from coding_installers import render_installer
from distribution_contracts import sha256_file


class ReleaseAssetsTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.packages = self.root / "packages"
        self.release = self.root / "release"
        self.tag = "v0.1.6"
        for group, filenames in {
            "sdk": ["haifa-agent-sdk-v0.1.6.zip"],
            "linux": ["haifa-coding-agent-linux-x64-v0.1.6.tar.gz"],
            "windows": [
                "haifa-coding-agent-windows-x64-v0.1.6.zip",
                "haifa-personal-assistant-linux-x64-v0.1.6.zip",
                "haifa-personal-assistant-windows-x64-v0.1.6.zip",
            ],
        }.items():
            directory = self.packages / group
            directory.mkdir(parents=True)
            lines = []
            for name in filenames:
                archive = directory / name
                archive.write_bytes(name.encode())
                lines.append(f"{sha256_file(archive)}  {name}")
                if name.startswith("haifa-coding-agent-"):
                    render_installer(archive, directory)
            (directory / "sha256sums.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")

    def test_incomplete_manifest_rejects_linux_package(self):
        (self.packages / "linux" / "sha256sums.txt").write_text("", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Invalid linux checksum manifest"):
            release_assets.assemble(self.tag, self.packages, self.release)

    def test_stale_installer_cannot_be_published_with_a_different_archive(self):
        script = self.packages / "windows" / "install-haifa.ps1"
        script.write_text(script.read_text(encoding="utf-8").replace("v0.1.6", "v0.1.5"), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Installer does not match"):
            release_assets.assemble(self.tag, self.packages, self.release)

    def test_draft_requires_complete_uploads_and_matching_digests(self):
        release_assets.assemble(self.tag, self.packages, self.release)
        manifest = (self.release / "sha256sums.txt").read_text(encoding="utf-8")
        self.assertEqual(5, len(manifest.splitlines()))
        self.assertIn("haifa-coding-agent-linux-x64-v0.1.6.tar.gz", manifest)
        self.assertIn("haifa-coding-agent-windows-x64-v0.1.6.zip", manifest)
        assets = [
            {"name": path.name, "state": "uploaded", "digest": f"sha256:{sha256_file(path)}"}
            for path in self.release.iterdir()
        ]
        draft = {"isDraft": True, "assets": assets}
        release_assets.verify_draft(self.release, draft)
        for name in ("sha256sums.txt", "haifa-coding-agent-linux-x64-v0.1.6.tar.gz", "install-haifa.ps1"):
            with self.subTest(missing=name):
                incomplete = [asset for asset in assets if asset["name"] != name]
                with self.assertRaisesRegex(ValueError, name):
                    release_assets.verify_draft(self.release, {**draft, "assets": incomplete})
        assets[0]["digest"] = None
        with self.assertRaisesRegex(ValueError, "Wrong digest"):
            release_assets.verify_draft(self.release, draft)
