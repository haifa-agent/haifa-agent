import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from cache_job import cache_round_trip


class CacheJobTest(unittest.TestCase):
    def test_uses_configured_temp_root(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.dict(os.environ, {"TMPDIR": temporary}):
                self.assertEqual("ok", cache_round_trip("ok"))
            self.assertEqual(
                "ok",
                (Path(temporary) / "haifa-python-cache" / "entry.txt").read_text(),
            )


if __name__ == "__main__":
    unittest.main()
