import importlib.util
import os
import sys
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "run_ladder.py"
SPEC = importlib.util.spec_from_file_location("run_ladder", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

PROMPT = "First line of the statement.\n\n- a bullet\n- another bullet\n"


def settings(**overrides):
    defaults = {
        "action": "run",
        "allow_real_provider": True,
        "agent": "haifa-coding",
        "model": None,
        "credential_env": None,
        "approval": "auto",
        "case_patterns": [],
        "repeat": 1,
        "timeout_scale": 1.0,
        "output_dir": Path("out"),
        "cache_dir": Path("cache"),
        "assets_dir": None,
        "skip_gates": True,
        "gate_repeat": 1,
        "rehearse": False,
        "keep_workdir": False,
    }
    defaults.update(overrides)
    return MODULE.Settings(**defaults)


class CredentialTest(unittest.TestCase):
    def test_model_prefix_selects_the_provider_variable(self):
        self.assertEqual("BIGMODEL_API_KEY", MODULE.credential_variable("glm-5.3-flash", None)[0])
        self.assertEqual("KIMI_API_KEY", MODULE.credential_variable("kimi-k3", None)[0])

    def test_model_auth_providers_need_no_variable(self):
        variable, reason = MODULE.credential_variable("deepseek-responses-flash", None)

        self.assertIsNone(variable)
        self.assertIn("auth.json", reason)

    def test_explicit_variable_wins(self):
        self.assertEqual("MY_KEY", MODULE.credential_variable("glm-5.3-flash", "MY_KEY")[0])

    def test_secret_values_are_redacted(self):
        os.environ["BIGMODEL_API_KEY"] = "super-secret-value"
        try:
            secrets = MODULE.secret_values(settings())
            line = MODULE.redact("calling provider with super-secret-value", secrets)
        finally:
            os.environ.pop("BIGMODEL_API_KEY", None)

        self.assertNotIn("super-secret-value", line)
        self.assertIn("***", line)


class LauncherTest(unittest.TestCase):
    def test_batch_launcher_is_replaced_by_the_packaged_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            distribution = Path(directory)
            launcher = distribution / "haifa-coding.cmd"
            launcher.write_text("@echo off\n", encoding="utf-8")
            (distribution / "haifa-agent.jar").write_text("", encoding="utf-8")
            (distribution / "haifa-coding.yaml").write_text("models: {}\n", encoding="utf-8")

            argv, note = MODULE.launcher_argv(str(launcher))

        self.assertEqual(["-jar", str(distribution / "haifa-agent.jar"), "--config", str(distribution / "haifa-coding.yaml")], argv[1:])
        self.assertIn("haifa-agent.jar", note)

    def test_batch_launcher_without_a_jar_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            launcher = Path(directory) / "haifa-coding.cmd"
            launcher.write_text("@echo off\n", encoding="utf-8")

            with self.assertRaises(SystemExit) as raised:
                MODULE.launcher_argv(str(launcher))

        self.assertIn("truncate", str(raised.exception))

    def test_plain_executable_is_used_as_is(self):
        self.assertEqual((["haifa-coding"], "haifa-coding"), MODULE.launcher_argv("haifa-coding"))

    def test_statement_stays_one_multi_line_argument(self):
        argv = MODULE.agent_argv(settings(model="glm-5.3-flash"), Path("ws"), PROMPT, 600)

        self.assertEqual(PROMPT, argv[-1])
        self.assertEqual("-m", argv[-2])
        self.assertIn("--workspace", argv)
        self.assertIn("--model", argv)
        self.assertIn("PT540S", argv)


class SelectionTest(unittest.TestCase):
    def test_patterns_filter_the_available_cases(self):
        with tempfile.TemporaryDirectory() as directory:
            cases_root = Path(directory)
            for case_id in ("L1-01", "L1-02", "L4-03", "notes"):
                (cases_root / case_id).mkdir()

            self.assertEqual(["L1-01", "L1-02", "L4-03"], MODULE.select_cases(cases_root, []))
            self.assertEqual(["L1-01", "L1-02"], MODULE.select_cases(cases_root, ["L1-*"]))
            self.assertEqual(["L1-02", "L4-03"], MODULE.select_cases(cases_root, ["L1-02", "L4-03"]))

    def test_missing_environment_requires_the_cost_acknowledgement(self):
        problems = MODULE.missing_environment(settings(allow_real_provider=False))

        self.assertTrue(any("HAIFA_LADDER_ALLOW_REAL_PROVIDER" in problem for problem in problems))

    def test_rehearsal_needs_no_agent_or_credential(self):
        self.assertEqual([], MODULE.missing_environment(settings(agent=None, rehearse=True, allow_real_provider=False)))


class DiagnosticsTest(unittest.TestCase):
    def test_changed_sources_and_reasons_are_extracted(self):
        stderr = 'noise\nDIAGNOSTICS {"changedSources": ["a.py"], "details": {"functional.x": "returned 3"}}\n'

        changed, reasons = MODULE.parse_diagnostics(stderr)

        self.assertEqual(["a.py"], changed)
        self.assertEqual({"functional.x": "returned 3"}, reasons)

    def test_missing_diagnostics_is_not_an_error(self):
        self.assertEqual(([], {}), MODULE.parse_diagnostics("acceptance crashed"))

    def test_duration_is_human_readable(self):
        self.assertEqual("45s", MODULE.duration(45))
        self.assertEqual("2m05s", MODULE.duration(125))
        self.assertEqual("1h01m", MODULE.duration(3700))


class SettingsTest(unittest.TestCase):
    def test_flags_win_over_environment(self):
        os.environ["HAIFA_LADDER_MODEL"] = "from-environment"
        try:
            resolved = MODULE.resolve_settings(
                Namespace(
                    action="check",
                    agent="agent",
                    model="from-flag",
                    credential_env=None,
                    approval=None,
                    cases="L2-*",
                    repeat=2,
                    timeout_scale=None,
                    output=None,
                    cache_dir=None,
                    assets_dir=None,
                    skip_gates=True,
                    gate_repeat=1,
                    rehearse=False,
                    keep_workdir=False,
                )
            )
        finally:
            os.environ.pop("HAIFA_LADDER_MODEL", None)

        self.assertEqual("from-flag", resolved.model)
        self.assertEqual(["L2-*"], resolved.case_patterns)
        self.assertEqual(2, resolved.repeat)
        self.assertEqual("auto", resolved.approval)


if __name__ == "__main__":
    unittest.main()
