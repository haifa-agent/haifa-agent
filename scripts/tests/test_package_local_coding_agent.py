import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "package-local-coding-agent.py"
SPEC = importlib.util.spec_from_file_location("package_local_coding_agent", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class PackageLocalCodingAgentTest(unittest.TestCase):
    def test_renders_current_model_api_configuration_for_both_launchers(self) -> None:
        repository = Path(__file__).resolve().parents[2]
        template = (
            repository
            / "haifa-agent-applications"
            / "haifa-agent-cli"
            / "distribution"
            / "haifa-coding.yaml"
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "haifa-coding.yaml"
            MODULE.render_configuration(template, output, root / "data")
            rendered = output.read_text(encoding="utf-8")

        MODULE.validate_model_configuration(rendered)
        self.assertIn("allowedBindings:", rendered)
        self.assertIn("deepseek-responses-flash", rendered)
        self.assertIn("deepseek-chat-pro", rendered)
        self.assertIn("endpoint: https://api.deepseek.com", rendered)
        self.assertIn("credentialRef: model-auth://deepseek/default", rendered)
        self.assertNotIn("apiBindings:", rendered)
        self.assertNotIn("CHATGPT2API_", rendered)
        self.assertNotIn("dialectVersion:", rendered)
        self.assertNotIn("__HAIFA_SQLITE_DATABASE_PATH__", rendered)
        self.assertNotIn("__HAIFA_TRANSCRIPT_ROOT__", rendered)
        self.assertNotIn("git-delivery", rendered)
        self.assertIn("allowed: [task-planning, result-verification]", rendered)
        self.assertNotIn("git, github", rendered)

    def test_refuses_a_template_without_both_persistence_tokens(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            template = root / "template.yaml"
            template.write_text(
                "allowedBindings: [deepseek-responses-flash, deepseek-chat-pro]\n"
                "credentialRef: model-auth://deepseek/default\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "exactly one"):
                MODULE.render_configuration(template, root / "out.yaml", root / "data")

    def test_generated_launchers_pass_tui_through_without_the_bundled_config(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            MODULE.write_windows_launcher(root)
            MODULE.write_posix_launcher(root)

            windows = (root / "haifa-coding.cmd").read_text(encoding="utf-8")
            posix = (root / "haifa-coding").read_text(encoding="utf-8")

        # The remote TUI branch must come before the bundled --config call and before any data directory creation.
        windows_tui = windows.index('if /i "%~1"=="tui"')
        posix_tui = posix.index('[ "${1:-}" = "tui" ]')
        self.assertLess(windows_tui, windows.index('haifa-agent.jar" --config'))
        self.assertLess(windows_tui, windows.index('mkdir "%HAIFA_DISTRIBUTION_DIR%data'))
        self.assertLess(posix_tui, posix.index('haifa-agent.jar" --config'))
        self.assertLess(posix_tui, posix.index("mkdir -p"))

    def test_generated_launchers_keep_the_host_subcommand_first(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            MODULE.write_windows_launcher(root)
            MODULE.write_posix_launcher(root)

            windows = (root / "haifa-coding.cmd").read_text(encoding="utf-8")
            posix = (root / "haifa-coding").read_text(encoding="utf-8")

        self.assertIn('jar" host --config', windows)
        self.assertIn('"$subcommand"', posix)
        self.assertLess(posix.index('if [ "${1:-}" = "host" ]'), posix.index('"$subcommand"'))

    @unittest.skipIf(os.name == "nt", "POSIX launcher process contract")
    def test_posix_launcher_forwards_the_tui_vector_and_creates_no_local_directories(self) -> None:
        # A child process is required to observe the real argument vector, the inherited environment and the
        # absence of the local data directories this mode must never create.
        with tempfile.TemporaryDirectory(prefix="ca tui launcher ") as temporary:
            root = Path(temporary)
            MODULE.write_posix_launcher(root)
            (root / "haifa-agent.jar").write_text("", encoding="utf-8")
            capture = root / "captured.json"
            # The launcher prefers "$JAVA_HOME/bin/java" when it is executable.
            (root / "bin").mkdir()
            fake_java = root / "bin" / "java"
            fake_java.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, sys\n"
                "from pathlib import Path\n"
                'Path(os.environ["CAPTURE_PATH"]).write_text(json.dumps({'
                '"args": sys.argv[1:], "db": os.environ.get("HAIFA_SQLITE_DATABASE_PATH")}))\n'
                "sys.exit(0)\n",
                encoding="utf-8",
            )
            fake_java.chmod(0o755)
            environment = dict(os.environ, CAPTURE_PATH=str(capture), JAVA_HOME=str(root))
            options = ["tui", "--connect", "http://127.0.0.1:17861", "--new"]

            result = subprocess.run(
                [str(root / "haifa-coding"), *options], env=environment, timeout=10
            )

            actual = json.loads(capture.read_text())
            self.assertEqual(result.returncode, 0)
            self.assertEqual(actual["args"][:2], ["-jar", str(root / "haifa-agent.jar")])
            self.assertEqual(actual["args"][2:], options)
            self.assertIsNone(actual["db"])
            self.assertFalse((root / "data").exists())
            self.assertFalse((root / "logs").exists())


if __name__ == "__main__":
    unittest.main()
