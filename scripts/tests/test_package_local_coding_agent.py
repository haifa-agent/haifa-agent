import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "package-local-coding-agent.py"
SPEC = importlib.util.spec_from_file_location("package_local_coding_agent", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class PackageLocalCodingAgentTest(unittest.TestCase):
    def test_build_command_uses_the_project_unit_test_skip_property(self) -> None:
        command = MODULE.cli_build_command(Path("mvnw.cmd"))

        self.assertIn("-DskipUnitTests=true", command)
        self.assertNotIn("-DskipTests", command)

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
        self.assertIn("apiBindings:", rendered)
        self.assertIn("style: anthropic-messages", rendered)
        self.assertIn("endpoint: https://api.deepseek.com/anthropic", rendered)
        self.assertIn("credentialRef: env://OPENAI_API_KEY", rendered)
        self.assertNotIn("CHATGPT2API_", rendered)
        self.assertNotIn("dialectVersion:", rendered)
        self.assertNotIn("__HAIFA_SQLITE_DATABASE_PATH__", rendered)
        self.assertNotIn("__HAIFA_TRANSCRIPT_ROOT__", rendered)

    def test_rejects_retired_model_configuration(self) -> None:
        with self.assertRaisesRegex(ValueError, "retired model configuration"):
            MODULE.validate_model_configuration(
                "apiBindings:\n"
                "style: openai-responses\n"
                "endpoint: ${OPENAI_BASE_URL:http://127.0.0.1:30000/v1}\n"
                "credentialRef: env://OPENAI_API_KEY\n"
                "providerModelId: ${OPENAI_MODEL_ID:test}\n"
                "dialectVersion: '1.0'\n"
            )

    def test_shaded_jar_validation_requires_tui4j_cleanup_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            jar_file = Path(temporary) / "haifa-agent.jar"
            with zipfile.ZipFile(jar_file, "w") as archive:
                archive.writestr(
                    "META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\nMain-Class: io.haifa.agent.cli.HaifaCliMain\n",
                )
                for entry in MODULE.REQUIRED_SHADED_JAR_ENTRIES:
                    archive.writestr(entry, b"class")

            MODULE.validate_shaded_jar(jar_file)
            with zipfile.ZipFile(jar_file, "w") as archive:
                archive.writestr(
                    "META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\nMain-Class: io.haifa.agent.cli.HaifaCliMain\n",
                )
                archive.writestr(
                    "io/haifa/agent/cli/HaifaCliMain.class", b"class"
                )
                archive.writestr(
                    "com/williamcallahan/tui4j/compat/bubbletea/ProgramCore.class",
                    b"class",
                )

            with self.assertRaisesRegex(RuntimeError, "ProgramCleanup.class"):
                MODULE.validate_shaded_jar(jar_file)


if __name__ == "__main__":
    unittest.main()
