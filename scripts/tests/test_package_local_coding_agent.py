import importlib.util
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


if __name__ == "__main__":
    unittest.main()
