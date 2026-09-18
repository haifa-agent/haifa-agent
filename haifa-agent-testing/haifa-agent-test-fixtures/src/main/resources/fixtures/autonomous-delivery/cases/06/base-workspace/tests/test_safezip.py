import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from safezip import extract_archive


class SafeZipTest(unittest.TestCase):
    def test_extracts_normal_nested_archive(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "safe.zip"
            with ZipFile(archive, "w") as target:
                target.writestr("docs/", b"")
                target.writestr("docs/readme.txt", "hello")
                target.writestr("资料/说明.txt", "ok")
            destination = root / "output"
            extract_archive(archive, destination)
            self.assertEqual("hello", (destination / "docs/readme.txt").read_text())
            self.assertEqual("ok", (destination / "资料/说明.txt").read_text())


if __name__ == "__main__":
    unittest.main()
