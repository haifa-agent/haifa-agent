import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "suggest_maven_scope.py"
SPEC = importlib.util.spec_from_file_location("suggest_maven_scope", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class SuggestMavenScopeTest(unittest.TestCase):
    def test_discovers_dependency_and_consumer_closures(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_pom(root / "pom.xml", "root")
            self.write_pom(root / "api" / "pom.xml", "api")
            self.write_pom(root / "impl" / "pom.xml", "impl", ["api"])
            self.write_pom(root / "app" / "pom.xml", "app", ["impl"])

            modules = MODULE.discover_modules(root)

            self.assertEqual({"api"}, MODULE.dependency_closure({"impl"}, modules) - {"impl"})
            self.assertEqual({"impl", "app"}, MODULE.consumer_closure({"api"}, modules))
            self.assertEqual("impl", MODULE.module_for_path("impl/src/main/java/App.java", modules))

    def test_root_pom_and_workflow_are_high_risk(self):
        reasons = MODULE.risk_reasons(["pom.xml", ".github/workflows/verify.yml"])
        self.assertGreaterEqual(len(reasons), 2)

    @staticmethod
    def write_pom(path: Path, artifact: str, dependencies=None):
        path.parent.mkdir(parents=True, exist_ok=True)
        dependency_xml = "".join(
            f"<dependency><groupId>io.haifa</groupId><artifactId>{value}</artifactId></dependency>"
            for value in (dependencies or [])
        )
        path.write_text(
            "<project><modelVersion>4.0.0</modelVersion>"
            f"<groupId>io.haifa</groupId><artifactId>{artifact}</artifactId>"
            f"<dependencies>{dependency_xml}</dependencies></project>",
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
