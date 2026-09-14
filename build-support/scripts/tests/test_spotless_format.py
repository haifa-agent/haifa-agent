import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

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

    def test_file_to_spotless_pattern(self):
        root = Path("/workspace/haifa-agent")
        pattern = MODULE.file_to_spotless_pattern(root, "core/src/App.java")
        self.assertRegex(r"D:\workspace\haifa-agent\core\src\App.java", pattern)
        self.assertRegex("/home/runner/haifa-agent/core/src/App.java", pattern)
        self.assertNotRegex("/home/runner/haifa-agent/other/src/App.java", pattern)

        root_pattern = MODULE.file_to_spotless_pattern(root, "pom.xml")
        self.assertRegex(r"D:\workspace\haifa-agent\pom.xml", root_pattern)
        self.assertNotRegex(r"D:\workspace\haifa-agent\core\pom.xml", root_pattern)

    def test_parse_pre_push_stdin(self):
        raw = (
            "refs/heads/feat-test 1111111111111111111111111111111111111111 "
            "refs/heads/feat-test 2222222222222222222222222222222222222222\n"
            "refs/heads/delete-branch 0000000000000000000000000000000000000000 "
            "refs/heads/delete-branch 3333333333333333333333333333333333333333\n\n"
        )
        entries = MODULE.parse_pre_push_stdin(raw)
        self.assertEqual(2, len(entries))
        self.assertEqual("refs/heads/feat-test", entries[0][0])
        self.assertEqual("1111111111111111111111111111111111111111", entries[0][1])
        self.assertEqual("refs/heads/feat-test", entries[0][2])
        self.assertEqual("2222222222222222222222222222222222222222", entries[0][3])
        self.assertEqual("0000000000000000000000000000000000000000", entries[1][1])

    def test_get_git_files_for_push(self):
        root = Path(".")

        # Fast-forward push
        with patch("subprocess.run") as mock_run:
            mock_run.side_effect = [
                MagicMock(returncode=0),
                MagicMock(returncode=0, stdout=b"core/src/App.java\0pom.xml\0"),
            ]
            stdin = "refs/heads/feat 2222 refs/heads/feat 1111\n"
            self.assertEqual(
                ["core/src/App.java", "pom.xml"],
                MODULE.get_git_files_for_push(root, stdin_text=stdin),
            )

        # Branch delete
        stdin_delete = "refs/heads/feat 0000000000000000000000000000000000000000 refs/heads/feat 1111\n"
        self.assertEqual([], MODULE.get_git_files_for_push(root, stdin_text=stdin_delete))

        # Force push / rebase
        with patch("subprocess.run") as mock_run:
            mock_run.side_effect = [
                MagicMock(returncode=1),
                MagicMock(returncode=0, stdout=b"apps/Main.java\0"),
            ]
            stdin_force = "refs/heads/feat 2222 refs/heads/feat 1111\n"
            self.assertEqual(
                ["apps/Main.java"],
                MODULE.get_git_files_for_push(root, stdin_text=stdin_force),
            )

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

