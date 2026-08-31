import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "codebase_stats.py"
SPEC = importlib.util.spec_from_file_location("codebase_stats", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


class CodebaseStatsTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)

    def build_tree(self):
        write(
            self.root / "pom.xml",
            "<project><modules><module>alpha</module><module>beta</module></modules></project>",
        )
        write(self.root / "alpha" / "pom.xml", "<project/>")
        write(self.root / "alpha" / "src" / "main" / "java" / "A.java", "a\nb\nc\n")
        write(self.root / "alpha" / "src" / "test" / "java" / "ATest.java", "x\ny\n")
        write(self.root / "beta" / "pom.xml", "<project/>")
        write(self.root / "beta" / "src" / "main" / "java" / "B.java", "1\n2\n")
        # 脚本目录
        write(self.root / "build-support" / "scripts" / "tool.py", "a\nb\n")
        write(self.root / "build-support" / "scripts" / "tool.sh", "x\n")
        write(self.root / "build-support" / "scripts" / "tool.ps1", "y\ny\ny\n")
        write(self.root / "scripts" / "other.py", "z\n")
        # docs 目录
        write(self.root / "docs" / "README.md", "top\n")
        write(self.root / "docs" / "guide" / "deep.md", "1\n2\n3\n4\n")
        write(self.root / "docs" / "guide" / "extra" / "nested.md", "5\n6\n7\n")

    def test_discovers_maven_modules_including_nested(self):
        self.build_tree()
        modules = MODULE.discover_maven_modules(self.root)
        self.assertEqual(
            ["alpha", "beta"],
            [path.relative_to(self.root).as_posix() for path in modules],
        )

    def test_module_stats_separate_main_and_test(self):
        self.build_tree()
        rows = MODULE.maven_module_stats(self.root)
        by_name = {row["module"]: row for row in rows}
        self.assertEqual(by_name["alpha"]["mainFiles"], 1)
        self.assertEqual(by_name["alpha"]["mainLines"], 3)
        self.assertEqual(by_name["alpha"]["testFiles"], 1)
        self.assertEqual(by_name["alpha"]["testLines"], 2)
        self.assertEqual(by_name["beta"]["mainLines"], 2)
        self.assertEqual(by_name["beta"]["testLines"], 0)

    def test_script_dir_stats_by_extension(self):
        self.build_tree()
        rows = MODULE.script_dir_stats(self.root, ["build-support/scripts", "scripts"])
        by_key = {(row["directory"], row["extension"]): row for row in rows}
        self.assertEqual(by_key[("build-support/scripts", ".py")]["lines"], 2)
        self.assertEqual(by_key[("build-support/scripts", ".sh")]["lines"], 1)
        self.assertEqual(by_key[("build-support/scripts", ".ps1")]["lines"], 3)
        self.assertEqual(by_key[("scripts", ".py")]["lines"], 1)

    def test_script_dir_stats_skips_missing_directory(self):
        self.build_tree()
        rows = MODULE.script_dir_stats(self.root, ["does-not-exist"])
        self.assertEqual(rows, [])

    def test_docs_stats_grouped_by_subdirectory(self):
        self.build_tree()
        stats = MODULE.docs_stats(self.root / "docs")
        self.assertEqual(stats["totalFiles"], 3)
        self.assertEqual(stats["totalLines"], 8)
        self.assertEqual(stats["averageLines"], 2.7)
        by_directory = {group["directory"]: group for group in stats["groups"]}
        self.assertEqual(by_directory["."]["files"], 1)
        self.assertEqual(by_directory["."]["lines"], 1)
        self.assertEqual(by_directory["."]["average"], 1.0)
        self.assertEqual(by_directory["guide"]["files"], 2)
        self.assertEqual(by_directory["guide"]["lines"], 7)
        self.assertEqual(by_directory["guide"]["average"], 3.5)

    def test_missing_root_pom_raises(self):
        with self.assertRaises(RuntimeError):
            MODULE.discover_maven_modules(self.root)


if __name__ == "__main__":
    unittest.main()
