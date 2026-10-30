"""Focused checks for how the release smoke selects artifacts; no packaging runs here."""

import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "dist-support/scripts/smoke_distributions.py"
SPEC = importlib.util.spec_from_file_location("smoke_distributions", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class ChecksummedArchiveTest(unittest.TestCase):
    def test_selects_the_checksummed_archive_and_ignores_stale_packages(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            current = "haifa-coding-agent-windows-x64-v0.1.5-SNAPSHOT.zip"
            stale = "haifa-coding-agent-windows-x64-v0.1.4-SNAPSHOT.zip"
            for name in (current, stale):
                (output / name).write_bytes(name.encode())
            selected = MODULE.checksummed_archive(
                output, {current: "0" * 64}, "haifa-coding-agent-windows-x64-*.zip"
            )
            self.assertEqual(selected, output / current)

    def test_refuses_a_checksum_set_without_exactly_one_match(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            sdk = "haifa-agent-sdk-v0.1.5-SNAPSHOT.zip"
            (output / sdk).write_bytes(b"x")
            with self.assertRaisesRegex(RuntimeError, "exactly one checksummed archive"):
                MODULE.checksummed_archive(output, {sdk: "0" * 64}, "haifa-coding-agent-windows-x64-*.zip")


if __name__ == "__main__":
    unittest.main()
