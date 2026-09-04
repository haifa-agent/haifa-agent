import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "spotless_format.py"
SPEC = importlib.util.spec_from_file_location("spotless_format", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class SpotlessFormatTest(unittest.TestCase):
    def test_is_spotless_target(self):
        self.assertTrue(MODULE.is_spotless_target("haifa-agent-core/src/main/java/App.java"))
        self.assertTrue(MODULE.is_spotless_target("pom.xml"))
        self.assertTrue(MODULE.is_spotless_target("README.md"))
        self.assertFalse(MODULE.is_spotless_target("target/App.class"))
        self.assertFalse(MODULE.is_spotless_target("docs/architecture.md"))
        self.assertFalse(MODULE.is_spotless_target(".git/hooks/pre-push"))

    def test_module_for_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_pom(root / "pom.xml", "haifa-agent-parent")
            self.write_pom(root / "core" / "pom.xml", "haifa-agent-runtime-core")

            modules = MODULE.discover_modules(root)
            self.assertEqual(
                "haifa-agent-runtime-core",
                MODULE.module_for_path("core/src/main/java/App.java", modules),
            )
            self.assertIsNone(MODULE.module_for_path("root_script.py", modules))

    @staticmethod
    def write_pom(path: Path, artifact: str):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            "<project><modelVersion>4.0.0</modelVersion>"
            f"<groupId>io.haifa</groupId><artifactId>{artifact}</artifactId></project>",
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
